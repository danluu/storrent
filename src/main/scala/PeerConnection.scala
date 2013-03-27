package org.storrent

import akka.actor.{ Actor, ActorRef, ActorLogging, Props }
import akka.util.ByteString
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await

import scala.concurrent.ExecutionContext.Implicits.global

class PeerConnection(ip: String, port: Int, fileManager: ActorRef, info_hash: Array[Int], fileLength: Long, pieceLength: Long) extends Actor with ActorLogging {
  import PeerConnection._

  implicit val askTimeout = Timeout(1.second)
  val peerTcp = context.actorOf(Props(new TCPClient(ip, port, self)), s"tcp-${ip}:${port}")
  var interested = false
  var choked = true
  var messageReader = handshakeReader _

  sendHandshake()

  // Assembly and send handshake
  def sendHandshake() = {
    val pstrlen: Array[Byte] = Array(19)
    val pstr = "BitTorrent protocol".getBytes
    val reserved: Array[Byte] = Array.fill(8){0}
    val info_hash_local: Array[Byte] = info_hash.map(_.toByte)
    val handshake: Array[Byte] = pstrlen ++ pstr ++ reserved ++ info_hash_local ++ info_hash_local //FIXME: peer_id should not be info_hash
    val handshakeBS: akka.util.ByteString = akka.util.ByteString.fromArray(handshake, 0, handshake.length)
    peerTcp ! TCPClient.SendData(handshakeBS)
  }

  // Send request for next piece iff there are pieces remaining and we're unchoked
  def requestNextPiece(fileManager: ActorRef, choked: Boolean) = {
    if (!choked) {
      val (index, validRequest) = Await.result(fileManager ? Torrent.PeerPieceRequest(self), 2.seconds).asInstanceOf[Tuple2[scala.collection.mutable.Set[Int], Boolean]]
      if (validRequest) { self ! GetPiece(index.head) }
    }
  }

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

  // Decode ID field of message and then execute some action
  def processMessage(m: ByteString) {
    val rest = m.drop(1)
    m(0) & 0xFF match {
      case 0 => //CHOKE
        println("CHOKE")
        choked = true
      case 1 => //UNCHOKE
        println("UNCHOKE")
        choked = false
        requestNextPiece(fileManager, choked)
      case 4 => //HAVE piece
        val index = bytesToInt(rest.take(4))
        println(s"HAVE ${index}")
        // The client will sometimes send us incorrect HAVE messages. Bad things happen if we request one of those pieces
        if (index < (fileLength / pieceLength + (fileLength % pieceLength) % 1)) {
          fileManager ! Torrent.PeerHas(index)
        }
      case 5 => //BITFIELD
        println(s"BITFIELD")
        var peerBitfieldSet: scala.collection.mutable.Set[Int] = scala.collection.mutable.Set()
        bitfieldToSet(rest, 0, peerBitfieldSet)
        peerBitfieldSet = peerBitfieldSet.filter(_ < (fileLength / pieceLength + (fileLength % pieceLength) % 1))
        fileManager ! Torrent.PeerHasBitfield(peerBitfieldSet)
      case 7 => //PIECE
        val index = bytesToInt(rest.take(4))
        //FIXME: we assume that offset within piece is always 0
        fileManager ! Torrent.ReceivedPiece(index, rest.drop(4).drop(4))
        println(s"PIECE ${rest.take(4)}")
        requestNextPiece(fileManager, choked)
    }
  }

  // Determine if we have at least one entire message. Return number of bytes consumed
  def parseFrame(localBuffer: ByteString): Int = {
    if (localBuffer.length < 4) // can't decode frame length
      return 0
    val length = bytesToInt(localBuffer.take(4))
    if (length > localBuffer.length - 4) // incomplete frame
      return 0

    if (length > 0) {           // watch out for 0 length keep-alive message
      val message = localBuffer.drop(4).take(length)
      processMessage(message)
    }
    length + 4
  }

  def bytesToInt(bytes: IndexedSeq[Byte]): Int = { java.nio.ByteBuffer.wrap(bytes.toArray).getInt }

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
          6, //id
          0, 0, 0, index.toByte, //index
          0, 0, 0, 0, //begin
          0, 0, 0x40, 0) //length = 16384
      val msg = akka.util.ByteString.fromArray(msgAr, 0, msgAr.length)
      println(s"sending request for piece: ${msg}")
      peerTcp ! TCPClient.SendData(msg)
  }
}

object PeerConnection {
  def ascii(bytes: ByteString): String = { bytes.decodeString("UTF-8").trim }

  case class ConnectToPeer()
  case class SendInterested()
  case class GetPiece(index: Int)
}
