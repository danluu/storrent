package org.storrent

import org.saunter.bencode._
import collection.immutable.ListMap
import java.net.URLEncoder
import scala.io.Source.{ fromInputStream }
import java.net._
import akka.actor.{ Actor, ActorRef, IO, IOManager, ActorLogging, Props, ActorSystem }
import akka.util.ByteString
import akka.pattern.ask
import akka.util._
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.parsing.combinator._
import scala.util.parsing.input._

object Snippets {
  val system = ActorSystem("storrent")
  val blob = system.actorOf(Props(new BigFIXMEObject()), "BigFIXMEObject")
  def main(args: Array[String]) {
    blob ! BigFIXMEObject.DoEverything
    system.scheduler.scheduleOnce(10.seconds) { system.shutdown() }
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

      val metaMap = decodedMeta.get match {
        case m: Map[String, Any] => m
        case m => println(m.getClass.getSimpleName); throw new ClassCastException
      }

      val infoMap = metaMap.get("info").get match {
        case m: Map[String, Any] => m
        case m => println(m.getClass.getSimpleName); throw new ClassCastException
      }
      val encodedInfoMap = BencodeEncoder.encode(infoMap)

      val fileLength = infoMap.get("length").get match {
        case m: Long => m
        case m => println(m.getClass.getSimpleName); throw new ClassCastException
      }
      val pieceLength = infoMap.get("piece length").get match {
        case m: Long => m
        case m => println(m.getClass.getSimpleName); throw new ClassCastException
      }

      val sparePiece = fileLength % pieceLength match {
        case 0 => 0
        case _ => 1
      }
      val numPieces = fileLength / pieceLength + sparePiece

      println(s"numPieces: ${numPieces}")

      val md = java.security.MessageDigest.getInstance("SHA-1")
      val infoSHABytes = md.digest(encodedInfoMap.getBytes).map(0xFF & _)
      val infoSHA = infoSHABytes.map { "%02x".format(_) }.foldLeft("") { _ + _ } //taken from Play


      def hexStringURLEncode(x: String) = {       //take a string that's already in hex and URLEncode it by putting a % in front of each pair
        x.grouped(2).toList.map("%" + _).mkString("")
      }

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
      val someTrackerResponse = decodedTrackerResponse.get match {
        case m: Map[String, String] => m
        case _ => throw new ClassCastException
      }

      val peers = someTrackerResponse.get("peers").get

      def peersToIp(allPeers: String) = {
        val peers = allPeers.getBytes.grouped(6).toList.map(_.map(0xFF & _))
        peers.foreach(x => println(x.mkString(".")))
        val ips = peers.map(x => x.slice(0, 4).mkString("."))
        val ports = peers.map { x =>
          (x(4) << 8) + x(5)
        }
        ips zip ports
      }

      val ipPorts = peersToIp(peers)

      ipPorts.foreach { p =>
        println(s"Connecting to ${p._1}:${p._2}")
        val peer = context.actorOf(Props(new PeerConnection(p._1, p._2, infoSHABytes, fileLength, pieceLength)), s"PeerConnection-${p._1}:${p._2}")
      }
  }

}

