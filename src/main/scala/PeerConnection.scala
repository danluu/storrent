package org.storrent

import akka.actor.{ Actor, ActorRef, ActorLogging, Props }
import akka.util.ByteString
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

// could make ip/port (peerName/hostName/whatever) in a structure
class PeerConnection(ip: String, port: Int, torrentManager: ActorRef, info_hash: Array[Int], fileLength: Long, pieceLength: Long) extends Actor with ActorLogging {
  import PeerConnection._
  import Frame._

  implicit val askTimeout = Timeout(1.second)
  var choked = true
  var messageReader = handshakeReader _

  val peerTcp = context.actorOf(Props(new TCPClient(ip, port, self)), s"tcp-${ip}:${port}")
  peerTcp ! TCPClient.SendData(createHandshakeFrame(info_hash)) // send handshake
  
  // Send request for next piece iff there are pieces remaining and we're unchoked
  def requestNextPiece(torrentManager: ActorRef, choked: Boolean) = {
    if (!choked) {
      val (index, validRequest) = Await.result(torrentManager ? Torrent.PeerPieceRequest(self), 2.seconds).asInstanceOf[Tuple2[mutable.Set[Int], Boolean]]
      if (validRequest) { self ! GetPiece(index.head) }
    }
  }

  def handshakeReader(LocalBuffer: ByteString): Int = {
    if (LocalBuffer.length < 68) {
      0
    } else {
      println("Sending Interested message")
      peerTcp ! TCPClient.SendData(createInterestedFrame())
      messageReader = parseFrame
      68
    }
  }

  def bitfieldToSet(bitfield: ByteString, index: Int, hasPiece: mutable.Set[Int]): Unit = {
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
        requestNextPiece(torrentManager, choked)
      case 4 => //HAVE piece
        val index = bytesToInt(rest.take(4))
        println(s"HAVE ${index}")
        // The client will sometimes send us incorrect HAVE messages. Bad things happen if we request one of those pieces
        if (index < (fileLength / pieceLength + (fileLength % pieceLength) % 1)) {
          torrentManager ! Torrent.PeerHas(index)
        }
        requestNextPiece(torrentManager, choked)
      case 5 => //BITFIELD
        println(s"BITFIELD")
        var peerBitfieldSet: mutable.Set[Int] = mutable.Set()
        bitfieldToSet(rest, 0, peerBitfieldSet)
        peerBitfieldSet = peerBitfieldSet.filter(_ < (fileLength / pieceLength + (fileLength % pieceLength) % 1))
        torrentManager ! Torrent.PeerHasBitfield(peerBitfieldSet)
      case 7 => //PIECE
        val index = bytesToInt(rest.take(4))
        //FIXME: we assume that offset within piece is always 0
        torrentManager ! Torrent.ReceivedPiece(index, rest.drop(4).drop(4))
        println(s"PIECE ${rest.take(4)}")
        requestNextPiece(torrentManager, choked)
    }
  }

  // Determine if we have at least one entire message. Return number of bytes consumed
  // could also move this function into another object (frame module thingy, no state)
  // instead, it returns both a number and a message
  // or 0, message object
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
    case GetPiece(index) =>
      val msg = createPieceFrame(index)
      println(s"sending request for piece: ${msg}")
      peerTcp ! TCPClient.SendData(msg)
  }
}

object PeerConnection {
  def ascii(bytes: ByteString): String = { bytes.decodeString("UTF-8").trim }

  case class ConnectToPeer()
  case class GetPiece(index: Int)
}
