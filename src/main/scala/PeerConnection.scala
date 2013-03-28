package org.storrent

import akka.actor.{ Actor, ActorRef, ActorLogging, Props }
import akka.util.ByteString
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

object PeerConnection {
  def bytesToInt(bytes: IndexedSeq[Byte]): Int = { java.nio.ByteBuffer.wrap(bytes.toArray).getInt }

  case class ConnectToPeer()
  case class GetPiece(index: Int)
}

// could make ip/port (peerName/hostName/whatever) in a structure
class PeerConnection(ip: String, port: Int, torrentManager: ActorRef, info_hash: Array[Int], fileLength: Long, pieceLength: Long) extends Actor with ActorLogging {
  import PeerConnection._
  import Frame._

  val numPieces = (fileLength / pieceLength + (fileLength % pieceLength) % 1)
  implicit val askTimeout = Timeout(1.second)

  var choked = true
  var messageReader = handshakeReader _

  val peerTcp = context.actorOf(Props(new TCPClient(ip, port, self)), s"tcp-${ip}:${port}")
  peerTcp ! TCPClient.SendData(createHandshakeFrame(info_hash)) // send handshake
  
  // Send request for next piece iff there are pieces remaining and we're unchoked
  def requestNextPiece(torrentManager: ActorRef, choked: Boolean) = {
    if (!choked) {
      val requestResult = Await.result(torrentManager ? Torrent.PeerPieceRequest(self), 2.seconds).asInstanceOf[Option[Int]]
      requestResult match {
        case None    => 
        case Some(i) =>
          peerTcp ! TCPClient.SendData(createPieceFrame(i))
      }
    }
  }

  // Return number of bytes to consume
  def handshakeReader(LocalBuffer: ByteString): Int = {
    if (LocalBuffer.length < 68) {
      0
    } else {
      println("Sending Interested message")
      peerTcp ! TCPClient.SendData(createInterestedFrame())
      messageReader = peerReader
      68
    }
  }

  // Return number of bytes to consume. Process message, if there is one
  def peerReader(localBuffer: ByteString): Int = {
    parseFrame(localBuffer) match {
      case (0,_) =>    0
      case (n,None) => n  // this case can happen (keep-alive message)
      case (n,Some(m)) => processMessage(m); n
    }
  }

  // Decode ID field of message and then execute some action
  def processMessage(m: ByteString) {
    val rest = m.drop(1)
    m(0) & 0xFF match {
      case 0 => // CHOKE
        println("CHOKE")
        choked = true
      case 1 => // UNCHOKE
        println("UNCHOKE")
        choked = false
        requestNextPiece(torrentManager, choked)
      case 4 => // HAVE piece
        val index = bytesToInt(rest.take(4))
        println(s"HAVE ${index}")
        // The client will sometimes send us incorrect HAVE messages. Bad things happen if we request one of those pieces
        if (index < numPieces) {
          torrentManager ! Torrent.PeerHas(index)
        }
        requestNextPiece(torrentManager, choked)
      case 5 => // BITFIELD
        println(s"BITFIELD")
        var peerBitfieldSet: mutable.Set[Int] = mutable.Set()
        bitfieldToSet(rest, 0, peerBitfieldSet)
        peerBitfieldSet = peerBitfieldSet.filter(_ < numPieces)
        torrentManager ! Torrent.PeerHasBitfield(peerBitfieldSet)
      case 7 => // PIECE
        val index = bytesToInt(rest.take(4))
        // FIXME: we assume that offset within piece is always 0
        torrentManager ! Torrent.ReceivedPiece(index, rest.drop(4).drop(4))
        println(s"PIECE ${rest.take(4)}")
        requestNextPiece(torrentManager, choked)
    }
  }

  def receive = {
    case TCPClient.DataReceived(buffer) =>
      sender ! messageReader(buffer)
    case TCPClient.ConnectionClosed =>
      println("")
  }
}
