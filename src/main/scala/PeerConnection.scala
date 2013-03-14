package org.storrent

import akka.actor.{ Actor, ActorRef, IO, IOManager, ActorLogging, Props, ActorSystem }
import akka.util.ByteString
import akka.pattern.ask
import akka.util._
import scala.concurrent.duration._
import scala.util.{ Success, Failure }

import scala.concurrent.ExecutionContext.Implicits.global


class PeerConnection() extends Actor with ActorLogging {
  import PeerConnection._

  import scala.collection.mutable.Map

  var subserver: ActorRef = self //FIXME: should make this a val that we initialize when the class is instantiated
  var handshakeSeen: Boolean = false
  var TcpReadBuffer: ByteString = akka.util.ByteString()
  var messageReader = handshakeReader _
  val hasPiece: scala.collection.mutable.Set[Int] = scala.collection.mutable.Set() //inefficient representation
  val weHavePiece: scala.collection.mutable.Set[Int] = scala.collection.mutable.Set()
  //FIXME: need a way to specify that we're currently downloading and should not request again

  def handshakeReader(LocalBuffer: ByteString): Int = {
    if (LocalBuffer.length < 68) {
      0
    } else {
      subserver ! SendInterested
      messageReader = parseFrame
      68
    }
  }

  def parseFrame(localBuffer: ByteString): Int = {
    if (localBuffer.length < 4)
      return 0
    val lengthBytes = localBuffer.take(4)
    val length = fourBytesToInt(lengthBytes)
    if (length > localBuffer.length - 4)
      return 0

    val message = localBuffer.drop(4).take(length)

    def processMessage(m: ByteString) {
      val rest = m.drop(1)
      m(0) & 0xFF match {
        case 1 => //UNCHOKE
          println("UNCHOKE")
          val missing = hasPiece -- weHavePiece
          subserver ! GetPiece(missing.head)
        case 4 => //HAVE piece
          val index = fourBytesToInt(rest.take(4))
          println(s"HAVE ${index}")
          hasPiece += index
        case 5 => //BITFIELD
          println(s"BITFIELD")
          def bitfieldToSet(index: Int): Unit = {
            //goes through each byte, and calls a function which goes through each bit and converts MSB:0 -> LSB:N in Set
            def byteToSet(byte: Byte, index: Int) = {
              def bitToSet(bit_index: Int): Unit = {
                //                    println(s"bitToSet with bit_index ${bit_index}. byte: ${byte} & ${1 << bit_index} = ${byte & (1 << bit_index)}")
                if ((byte & (1 << bit_index)) != 0) {
                  //                      println(s"adding ${8 * index + (7 - bit_index)}")
                  hasPiece += 8 * index + (7 - bit_index)
                }
                if (bit_index > 0) {
                  bitToSet(bit_index - 1)
                }
              }
              bitToSet(7)
            }
            byteToSet(rest.drop(index)(0), index)

            val newIndex = index + 1
            if (newIndex < rest.length)
              bitfieldToSet(newIndex)
          }
          bitfieldToSet(0)

          println(s"hasPiece: ${hasPiece}")
        case 7 => //PEICE
          println(s"PEICE ${rest.take(4)}")
          val index = fourBytesToInt(rest.take(4))
          weHavePiece += index
          val missing = hasPiece -- weHavePiece
          subserver ! GetPiece(missing.head)
      }
    }
    processMessage(message)
    return length + 4
  }

  def fourBytesToInt(bytes: IndexedSeq[Byte]): Int = {
    (bytes(0) << 8 * 3) + (bytes(1) << 8 * 2) + (bytes(2) << 8) + bytes(3)
  }

  val serverSocket = IOManager(context.system).listen("0.0.0.0", 31733)

  def receive = {
    //FIXME: only passing info_hash in because we're putting the handshake here
    case ConnectToPeer(ip, port, info_hash, fileLength, pieceLength) =>
      val socket = IOManager(context.system) connect (ip, port) //Ip, port
      subserver = context.actorOf(Props(new SubServer(socket)))
      //FIXME: this handshake should probably live somewhere else
      val pstrlen: Array[Byte] = Array(19)
      val pstr = "BitTorrent protocol".getBytes
      val reserved: Array[Byte] = Array(0, 0, 0, 0, 0, 0, 0, 0)
      //FIXME: peer_id should not be info_hash
      val info_hash_local: Array[Byte] = info_hash.map(_.toByte)
      val handshake: Array[Byte] = pstrlen ++ pstr ++ reserved ++ info_hash_local ++ info_hash_local
      val handshakeStr = (new String(handshake))
      val handshakeBS: akka.util.ByteString = akka.util.ByteString.fromArray(handshake, 0, handshake.length)
      socket write handshakeBS

    case IO.Listening(server, address) => log.info("TCP Server listeninig on port {}", address)
    case IO.NewClient(server) =>
      log.info("New incoming client connection on server")
      val socket = server.accept()
    //FIXME: we can't accept clients right now
    case IO.Read(_, bytes) =>
      TcpReadBuffer = TcpReadBuffer ++ bytes

      var bytesRead = 1
      while (bytesRead > 0) {
        bytesRead = messageReader(TcpReadBuffer)
        TcpReadBuffer = TcpReadBuffer.drop(bytesRead)
      }

    case IO.Closed(socket, cause) =>
      context.stop(subserver)
      log.info(s"connection to ${socket} closed: ${cause}")
  }
}

object PeerConnection {
  implicit val askTimeout = Timeout(1.second)
  val welcome = "return message thingy"
  def ascii(bytes: ByteString): String = {
    bytes.decodeString("UTF-8").trim
  }

  case class NewMessage(msg: String)
  case class ConnectToPeer(ip: String, port: Int, info_hash: Array[Int], fileLength: Long, pieceLength: Long)
  case class Handshake()
  case class SendInterested()
  case class GetPiece(index: Int)

  class SubServer(socket: IO.SocketHandle) extends Actor {
    //    var choked: Boolean = true
    //    var downloading: Boolean = false
    var interested: Boolean = false

    def receive = {
      case SendInterested =>
        if (!interested) {
          println("Sending Interested message")
          val msgAr: Array[Byte] = Array(0, 0, 0, 1, 2)
          val msg: ByteString = akka.util.ByteString.fromArray(msgAr, 0, msgAr.length)
          socket.write(msg)
        }
      case GetPiece(index) =>
        //FIXME: this assumes the index < 256
        //FIXME: hardcoding length because we know the file has piece size 16384
        val msgAr: Array[Byte] =
          Array(0, 0, 0, 13, //len
            6, //id
            0, 0, 0, index.toByte, //index
            0, 0, 0, 0, //begin
            0, 0, 0x40, 0) //length = 16384
        val msg = akka.util.ByteString.fromArray(msgAr, 0, msgAr.length)
        println(s"sending request for piece: ${msg}")
        socket.write(msg)
    }
  }
}
