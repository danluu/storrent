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
  case class RequestNextPiece(index: Int)
}

// could make ip/port (peerName/hostName/whatever) in a structure
class PeerConnection(ip: String, port: Int, torrentManager: ActorRef, info_hash: Array[Int], fileLength: Long, pieceLength: Long)
  extends Actor with ActorLogging with BTProtocolProvider {
  import PeerConnection._
  import BTProtocol._

  val btProtocol = context.actorOf(Props(newBTProtocol(ip, port, self, info_hash, fileLength, pieceLength)), s"BTP-${ip}:${port}")

  val numPieces = (fileLength / pieceLength + (fileLength % pieceLength) % 1)
  implicit val askTimeout = Timeout(1.second)
  var choked = true

  // Send request for next piece iff there are pieces remaining and we're unchoked
  def requestNextPiece(torrentManager: ActorRef, choked: Boolean) = {
    if (!choked) {
      val requestResult = Await.result(torrentManager ? Torrent.PeerPieceRequest(self), 2.seconds).asInstanceOf[Option[Int]]
      requestResult match {
        case None =>
        case Some(i) =>
          btProtocol ! RequestNextPiece(i)
      }
    }
  }

  def receive = {
    case Choke() =>
      choked = true
    case Unchoke() =>
      choked = false
      requestNextPiece(torrentManager, choked)
    case Have(index) =>
      // The client will sometimes send us incorrect HAVE messages. It won't respond to requests for those invalid pieces
      if (index < numPieces) {
        torrentManager ! Torrent.PeerHas(index)
      }
      requestNextPiece(torrentManager, choked)
    case Bitfield(peerBitfieldSet) =>
      // The client will sometimes send us incorrect BITFIELD messages. It won't respond to requests for those invalid pieces
      torrentManager ! Torrent.PeerHasBitfield(peerBitfieldSet.filter(_ < numPieces))
    case Piece(index, chunk) =>
      torrentManager ! Torrent.ReceivedPiece(index, chunk)
      requestNextPiece(torrentManager, choked)
  }
}
