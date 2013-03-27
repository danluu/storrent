package org.storrent

import akka.actor.{ Actor, ActorRef, ActorLogging, Props } 
import akka.util.ByteString
import akka.util.Timeout
import scala.concurrent.duration._
import akka.pattern.ask
import scala.concurrent.Await
import org.apache.commons.io.FileUtils.writeByteArrayToFile
import scala.concurrent.ExecutionContext.Implicits.global

object Torrent {
  case class DoEverything(torrentName: String)
  case class ReceivedPiece(index: Int, data: ByteString)
  case class WeHaveWhat
  case class PeerHas(index: Int)
  case class PeerPieceRequest(sendingActor: ActorRef)
  case class PeerHasBitfield(peerBitfieldSet: scala.collection.mutable.Set[Int])
  case class TrackerKeepAlive
}

class Torrent(torrentName: String) extends Actor with ActorLogging {
  import Torrent._

  implicit val timeout = Timeout(1.second)

  val weHavePiece: scala.collection.mutable.Set[Int] = scala.collection.mutable.Set()
  val peerHasPiece = scala.collection.mutable.Map.empty[ActorRef, scala.collection.mutable.Set[Int]] 
  val peerSeen: scala.collection.mutable.Set[String] = scala.collection.mutable.Set()
  val tracker = context.actorOf(Props(new Tracker(torrentName)), s"Tracker${torrentName}")

  var numPieces: Long = 0
  var fileContents: Array[ByteString] = Array()
  var ticker =  context.system.scheduler.schedule(0.seconds, 600.seconds, self, TrackerKeepAlive)

  def peersToIp(allPeers: String) = {
    val peers = allPeers.getBytes.grouped(6).toList.map(_.map(0xFF & _))
    peers.foreach(x => println(x.mkString(".")))
    val ips = peers.map(x => x.slice(0, 4).mkString("."))
    val ports = peers.map { x => (x(4) << 8) + x(5) } //convert 2 bytes to an int
    ips zip ports
  }

  def receive = {
    case ReceivedPiece(index, data) =>
      if (fileContents.length == 0)
        fileContents = Array.fill(numPieces.toInt) { akka.util.ByteString("") }
      fileContents(index) = data
      weHavePiece += index
      if (weHavePiece.size >= numPieces) {
        val file = new java.io.File("flag.jpg")
        fileContents.foreach { s => writeByteArrayToFile(file, s.toArray, true) }
        context.system.shutdown()
      }
    case PeerHas(index) =>
      peerHasPiece(sender) += index
    case PeerHasBitfield(peerBitfieldSet) =>
      peerHasPiece(sender) = peerBitfieldSet
    case PeerPieceRequest(sendingActor) => 
      val missing = peerHasPiece(sendingActor) -- weHavePiece
      val validRequest = missing.size > 0
      sender ! (missing, validRequest)
    case TrackerKeepAlive =>
      val (peers, infoSHABytes, fileLength, pieceLength, numP) = Await.result(tracker ? Tracker.PingTracker, 4.seconds) match { case (p: String, i: Array[Int], f: Long, pl: Long, np: Long) => (p, i, f, pl, np) }
      numPieces = numP
      val ipPorts = peersToIp(peers)
      ipPorts.foreach { p =>
        println(s"Connecting to ${p._1}:${p._2}")
        peerSeen.find{_ == s"${p._1}:${p._2}"} match {
          case Some(_) =>
          case None =>
            val peer = context.actorOf(Props(new PeerConnection(p._1, p._2, self, infoSHABytes, fileLength, pieceLength)), s"PeerConnection-${p._1}:${p._2}")
            peerHasPiece += (peer -> scala.collection.mutable.Set())
        }
      }

  }
}
