package org.storrent

import org.saunter.bencode._
import java.net.URLEncoder
import scala.io.Source.{ fromInputStream }
import java.net._
import akka.actor.{ Actor, ActorRef, IO, IOManager, ActorLogging, Props, ActorSystem }
import akka.util.ByteString
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import java.io._
import org.apache.commons.io.FileUtils.writeByteArrayToFile

object Snippets {
  val system = ActorSystem("storrent")
  val blob = system.actorOf(Props(new BigFIXMEObject()), "BigFIXMEObject")
  def main(args: Array[String]) {
    blob ! BigFIXMEObject.DoEverything
    system.scheduler.scheduleOnce(10.seconds) { system.shutdown() }
  }
}

object FileManager {
  case class ReceivedPiece(index: Int, data: ByteString)
  case class Finished
}

class FileManager(numPieces: Long) extends Actor with ActorLogging {
  val fileContents: Array[ByteString] = Array.fill(numPieces.toInt){akka.util.ByteString("")}

  import FileManager._

  def receive = {
    case ReceivedPiece(index, data) =>
      fileContents(index) = data
    case Finished =>
      
      val file = new java.io.File("flag.jpg")
      fileContents.foreach{s => writeByteArrayToFile(file, s.toArray, true)}
  }
}

object BigFIXMEObject {
  case class DoEverything
  case class HashRequest 
}

class BigFIXMEObject extends Actor with ActorLogging {
  import BigFIXMEObject._

  def receive = {
    case DoEverything =>

      val source = scala.io.Source.fromFile("tom.torrent", "macintosh")
      val metainfo = source.mkString
      source.close()
      val decodedMeta = BencodeDecoder.decode(metainfo)

      //this is a hack to get around type erasure warnings. It seems that the correct fix is to use the Manifest in the bencode library
      val metaMap = decodedMeta.get.asInstanceOf[Map[String,Any]]
      val infoMap = metaMap.get("info").get.asInstanceOf[Map[String,Any]]
      val fileLength = infoMap.get("length").get.asInstanceOf[Long]
      val pieceLength = infoMap.get("piece length").get.asInstanceOf[Long]
      val encodedInfoMap = BencodeEncoder.encode(infoMap)
      val numPieces = fileLength / pieceLength + (fileLength % pieceLength) % 1
      val md = java.security.MessageDigest.getInstance("SHA-1")
      val infoSHABytes = md.digest(encodedInfoMap.getBytes).map(0xFF & _)
      val infoSHA = infoSHABytes.map { "%02x".format(_) }.foldLeft("") { _ + _ } //taken from Play

      // take a string that's already in hex and URLEncode it by putting a % in front of each pair
      def hexStringURLEncode(x: String) = { x.grouped(2).toList.map("%" + _).mkString("") }

      println(s"numPieces ${numPieces}")

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
      val someTrackerResponse = decodedTrackerResponse.get.asInstanceOf[Map[String,String]]

      val peers = someTrackerResponse.get("peers").get

      def peersToIp(allPeers: String) = {
        val peers = allPeers.getBytes.grouped(6).toList.map(_.map(0xFF & _))
        peers.foreach(x => println(x.mkString(".")))
        val ips = peers.map(x => x.slice(0, 4).mkString("."))
        val ports = peers.map { x => (x(4) << 8) + x(5) } //convert 2 bytes to an int
        ips zip ports
      }

      val fm = context.actorOf(Props(new FileManager(numPieces)), s"FileManager${infoSHAEncoded}")

      val ipPorts = peersToIp(peers)
      ipPorts.foreach { p =>
        println(s"Connecting to ${p._1}:${p._2}")
        val peer = context.actorOf(Props(new PeerConnection(p._1, p._2, fm, infoSHABytes, fileLength, pieceLength)), s"PeerConnection-${p._1}:${p._2}")
      }
  }
}
