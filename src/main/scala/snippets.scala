package org.storrent

import org.saunter.bencode._
import java.net.URLEncoder
import scala.io.Source.{ fromInputStream }
import java.net._
import akka.actor.{ Actor, ActorRef, IO, IOManager, ActorLogging, Props, ActorSystem }
import akka.pattern.ask
import akka.util.Timeout
import akka.util.ByteString
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

import java.io._
import org.apache.commons.io.FileUtils.writeByteArrayToFile

object Snippets {
  val system = ActorSystem("storrent")
  val blob = system.actorOf(Props(new BigFIXMEObject()), "BigFIXMEObject")
  def main(args: Array[String]) {
    blob ! BigFIXMEObject.DoEverything("tom.torrent")
  }
}

object FileManager {
  case class ReceivedPiece(index: Int, data: ByteString)
  case class WeHaveWhat
}

class FileManager(numPieces: Long) extends Actor with ActorLogging {
  val fileContents: Array[ByteString] = Array.fill(numPieces.toInt) { akka.util.ByteString("") }
  val weHavePiece: scala.collection.mutable.Set[Int] = scala.collection.mutable.Set()

  import FileManager._

  def receive = {
    case ReceivedPiece(index, data) =>
      fileContents(index) = data
      weHavePiece += index
      if(weHavePiece.size >= numPieces){
        val file = new java.io.File("flag.jpg")
        fileContents.foreach { s => writeByteArrayToFile(file, s.toArray, true) }
        context.system.shutdown()
      }
    case WeHaveWhat =>
      sender ! weHavePiece
  }
}

object Tracker {
  case class PingTracker
}

class Tracker(torrentName: String) extends Actor with ActorLogging {
  import Tracker._

  def hexStringURLEncode(x: String) = { x.grouped(2).toList.map("%" + _).mkString("") }

  def receive = {
    case PingTracker =>
      val source = scala.io.Source.fromFile(torrentName, "macintosh")
      val metainfo = source.mkString
      source.close()
      val decodedMeta = BencodeDecoder.decode(metainfo)

      //this is a hack to get around type erasure warnings. It seems that the correct fix is to use the Manifest in the bencode library
      val metaMap = decodedMeta.get.asInstanceOf[Map[String, Any]]
      val infoMap = metaMap.get("info").get.asInstanceOf[Map[String, Any]]
      val fileLength = infoMap.get("length").get.asInstanceOf[Long]
      val pieceLength = infoMap.get("piece length").get.asInstanceOf[Long]
      val encodedInfoMap = BencodeEncoder.encode(infoMap)
      val numPieces = fileLength / pieceLength + (fileLength % pieceLength) % 1
      val md = java.security.MessageDigest.getInstance("SHA-1")
      val infoSHABytes = md.digest(encodedInfoMap.getBytes).map(0xFF & _)
      val infoSHA = infoSHABytes.map { "%02x".format(_) }.foldLeft("") { _ + _ } //taken from Play
      val infoSHAEncoded = hexStringURLEncode(infoSHA)
      val params = Map("port" -> "63211", "uploaded" -> "0", "downloaded" -> "0", "left" -> "1277987")
      val encodedParams = (for ((k, v) <- params) yield URLEncoder.encode(k) + "=" + URLEncoder.encode(v)).mkString("&")
      val infoSHAParam = s"info_hash=${infoSHAEncoded}"
      val peerIdParam = s"peer_id=${infoSHAEncoded}" //FIXME: peer id should obviously not be the same as our hash
      val allParams = s"?${infoSHAParam}&${peerIdParam}&${encodedParams}"
      val completeUrl = "http://thomasballinger.com:6969/announce" + allParams
      val url = new URL(completeUrl)
      val trackerResponse = fromInputStream(url.openStream, "macintosh").getLines.mkString("\n")
      val decodedTrackerResponse = BencodeDecoder.decode(trackerResponse)
      val someTrackerResponse = decodedTrackerResponse.get.asInstanceOf[Map[String, String]]
      val peers = someTrackerResponse.get("peers").get

      sender ! (peers, infoSHABytes, fileLength, pieceLength, numPieces)
  }
}

object BigFIXMEObject {
  case class DoEverything(torrentName: String)
  case class HashRequest
}

class BigFIXMEObject extends Actor with ActorLogging {
  import BigFIXMEObject._

  implicit val timeout = Timeout(1.second)

  def peersToIp(allPeers: String) = {
    val peers = allPeers.getBytes.grouped(6).toList.map(_.map(0xFF & _))
    peers.foreach(x => println(x.mkString(".")))
    val ips = peers.map(x => x.slice(0, 4).mkString("."))
    val ports = peers.map { x => (x(4) << 8) + x(5) } //convert 2 bytes to an int
    ips zip ports
  }

  def receive = {
    case DoEverything(torrentName) =>
      val tracker = context.actorOf(Props(new Tracker(torrentName)), s"Tracker${torrentName}")
      val (peers, infoSHABytes, fileLength, pieceLength, numPieces) = Await.result(tracker ? Tracker.PingTracker, 4.seconds) match { case (p: String, i: Array[Int], f: Long, pl: Long, np: Long) => (p, i, f, pl, np) }
      val fm = context.actorOf(Props(new FileManager(numPieces)), s"FileManager${torrentName}")
      val ipPorts = peersToIp(peers)
      ipPorts.foreach { p =>
        println(s"Connecting to ${p._1}:${p._2}")
        val peer = context.actorOf(Props(new PeerConnection(p._1, p._2, fm, infoSHABytes, fileLength, pieceLength)), s"PeerConnection-${p._1}:${p._2}")
      }
  }
}
