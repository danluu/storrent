package org.storrent

import akka.actor.{ Actor, ActorRef, IO, IOManager, ActorLogging, Props, ActorSystem }
import akka.util.ByteString
import akka.pattern.ask
import akka.util._
import scala.concurrent.duration._
import scala.concurrent.Await

import scala.concurrent.ExecutionContext.Implicits.global

object TCPClient {
  case class DataReceived(buffer: ByteString)
  case class ConnectionClosed
  case class CloseConnection
  case class SendData(bytes: ByteString)
}

class TCPClient(ip: String, port: Int, peer: ActorRef) extends Actor with ActorLogging {
  import TCPClient._

  val socket = IOManager(context.system) connect (ip, port) //Ip, port
  var buffer: ByteString = akka.util.ByteString()

  def receive = {
    case IO.Closed(socket, cause) =>
      log.info(s"connection to ${socket} closed: ${cause}")
      peer ! ConnectionClosed
      socket.close
    case IO.Read(_, bytes) =>
      buffer = buffer ++ bytes

      implicit val timeout = Timeout(5.seconds)
      var bytesRead = 0
      do {
        bytesRead = Await.result(peer ? DataReceived(buffer), 5.seconds).asInstanceOf[Int]
        buffer = buffer.drop(bytesRead)
      } while (bytesRead > 0)
    case SendData(bytes) =>
      socket write bytes
    case CloseConnection =>
      peer ! ConnectionClosed
      socket.close
  }
}

class PeerConnection(ip: String, port: Int, fileManager: ActorRef, info_hash: Array[Int], fileLength: Long, pieceLength: Long) extends Actor with ActorLogging {
  import PeerConnection._

  val peerTcp = context.actorOf(Props(new TCPClient(ip, port, self)), s"tcp-${ip}:${port}")

  var interested = false
  val pstrlen: Array[Byte] = Array(19)
  val pstr = "BitTorrent protocol".getBytes
  val reserved: Array[Byte] = Array(0, 0, 0, 0, 0, 0, 0, 0)
  //FIXME: peer_id should not be info_hash
  val info_hash_local: Array[Byte] = info_hash.map(_.toByte)
  val handshake: Array[Byte] = pstrlen ++ pstr ++ reserved ++ info_hash_local ++ info_hash_local
  val handshakeStr = (new String(handshake))
  val handshakeBS: akka.util.ByteString = akka.util.ByteString.fromArray(handshake, 0, handshake.length)
  peerTcp ! TCPClient.SendData(handshakeBS)

  var messageReader = handshakeReader _
  var hasPiece: scala.collection.mutable.Set[Int] = scala.collection.mutable.Set() //inefficient representation
  val weHavePiece: scala.collection.mutable.Set[Int] = scala.collection.mutable.Set()
  //FIXME: need a way to specify that we're currently downloading and should not request again

  def handshakeReader(LocalBuffer: ByteString): Int = {
    if (LocalBuffer.length < 68) {
      0
    } else {
      self ! SendInterested
      messageReader = parseFrame
      68
    }
  }

  def bitfieldToSet(bitfield: ByteString, index: Int, hasPiece: scala.collection.mutable.Set[Int]): Unit = {
    //goes through each byte, and calls a function which goes through each bit and converts MSB:0 -> LSB:N in Set
    def byteToSet(byte: Byte, index: Int) = {
      def bitToSet(bit_index: Int): Unit = {
        if ((byte & (1 << bit_index)) != 0) {
          hasPiece += 8 * index + (7 - bit_index)
        }
        if (bit_index > 0) {
          bitToSet(bit_index - 1)
        }
      }
      bitToSet(7)
    }
    byteToSet(bitfield.drop(index)(0), index)

    val newIndex = index + 1
    if (newIndex < bitfield.length)
      bitfieldToSet(bitfield, newIndex, hasPiece)
  }

  def parseFrame(localBuffer: ByteString): Int = {
    if (localBuffer.length < 4)
      return 0
    val lengthBytes = localBuffer.take(4)
    val length = bytesToInt(lengthBytes)
    if (length > localBuffer.length - 4)
      return 0

    val message = localBuffer.drop(4).take(length)

    def processMessage(m: ByteString) {
      val rest = m.drop(1)
      m(0) & 0xFF match {
        case 0 => //CHOKE
          println("CHOKE")
        case 1 => //UNCHOKE
          println("UNCHOKE")
          val missing = hasPiece -- weHavePiece
          self ! GetPiece(missing.head)
        case 4 => //HAVE piece
          val index = bytesToInt(rest.take(4))
          println(s"HAVE ${index}")
          // The client will sometimes send us incorrect HAVE messages. Bad things happen if we request one of those pieces
          if(index < (fileLength / pieceLength + (fileLength % pieceLength) % 1)){
            hasPiece += index
          }
        case 5 => //BITFIELD
          println(s"BITFIELD")
          bitfieldToSet(rest, 0, hasPiece)
          hasPiece = hasPiece.filter(_ < (fileLength / pieceLength + (fileLength % pieceLength) % 1))
          println(s"hasPiece: ${hasPiece}")
        case 7 => //PEICE
          val index = bytesToInt(rest.take(4))
          weHavePiece += index
          val missing = hasPiece -- weHavePiece
          //FIXME: we assume that offset within piece is always 0
          fileManager ! FileManager.ReceivedPiece(index, rest.drop(8))
          println(s"PEICE ${rest.take(4)}, need ${missing.size}")
          if (missing.size == 0){
            println("Received entire file")
            fileManager !  FileManager.Finished //FIXME: this is only needed while we (incorrectly) keep track of the file in here
          } else {
            self ! GetPiece(missing.head)
          }
      }
    }
    processMessage(message)
    return length + 4
  }

  def bytesToInt(bytes: IndexedSeq[Byte]): Int = { java.nio.ByteBuffer.wrap(bytes.toArray).getInt}

  def receive = {
    case TCPClient.DataReceived(buffer) =>
      sender ! messageReader(buffer)
    case TCPClient.ConnectionClosed =>
      println("")
    case SendInterested =>
      if (!interested) {
        println("Sending Interested message")
        val msgAr: Array[Byte] = Array(0, 0, 0, 1, 2)
        val msg: ByteString = akka.util.ByteString.fromArray(msgAr, 0, msgAr.length)
        peerTcp ! TCPClient.SendData(msg)
      }
    case GetPiece(index) =>
      //FIXME: this assumes the index < 256
      //FIXME: hardcoding length because we know the file has piece size 16384
//      val indexBytes = java.nio.ByteBuffer.allocate(4)
//      val aBytes: Array[Byte] = Array(indexBytes.putInt(index))
      val msgAr: Array[Byte] =
        Array(0, 0, 0, 13, //len
          6,  //id
          0, 0, 0, index.toByte, //index
//      aBytes ++ 
          0, 0, 0, 0, //begin
          0, 0, 0x40, 0) //length = 16384
      val msg = akka.util.ByteString.fromArray(msgAr, 0, msgAr.length)
      println(s"sending request for piece: ${msg}")
      peerTcp ! TCPClient.SendData(msg)
  }
}

object PeerConnection {
  implicit val askTimeout = Timeout(1.second)
  val welcome = "return message thingy"
  def ascii(bytes: ByteString): String = {
    bytes.decodeString("UTF-8").trim
  }

  case class ConnectToPeer()
  case class SendInterested()
  case class GetPiece(index: Int)
}
