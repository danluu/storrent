package org.storrent

import org.saunter.bencode._
//import dispatch._
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

//org.apache.http <- Daniel S recommended this (just use the java one)
//import org.apache.http.client._
//import org.apache.http.client.methods._
//import org.apache.http.impl.client._

import scala.util.parsing.combinator._
import scala.util.parsing.input._

object Snippets {
  val system = ActorSystem("storrent")
  val server = system.actorOf(Props(new TCPServer()),"TCPServer")

  def main(args: Array[String]) {
    //    val metainfoStream  = Resource.fromFile("tom.torrent").mkString
    val source = scala.io.Source.fromFile("tom.torrent", "macintosh")
    val metainfo = source.mkString
    source.close()
    val decodedMeta = BencodeDecoder.decode(metainfo)
    //    println(s"decoded torrent ${decodedMeta}")

    //    decodedMeta.get.foreach{x => println(s"ITEM: ${x}")}
    val metaMap = decodedMeta.get match {
      case m: Map[Any, Any] => m
      case m => println(m.getClass.getSimpleName); throw new ClassCastException
    }

    val infoMap = metaMap.get("info").get
    val encodedInfoMap = BencodeEncoder.encode(infoMap)
    //    val encodedInfoMap = BencodeEncoder.encode(List("foo","bar"))
    //    println(encodedInfoMap)

    val md = java.security.MessageDigest.getInstance("SHA-1")
    val infoSHABytes = md.digest(encodedInfoMap.getBytes).map(0xFF & _)
    val infoSHA = infoSHABytes.map { "%02x".format(_) }.foldLeft("") { _ + _ } //taken from Play
    println(s"hash into string: ${infoSHA}")

    //take a string that's already in hex and URLEncode it by putting a % in front of each pair
    def hexStringURLEncode(x: String) = {
      x.grouped(2).toList.map("%" + _).mkString("")
    }

    val infoSHAEncoded = hexStringURLEncode(infoSHA)
    println(infoSHAEncoded)

    val params = Map("port" -> "63211", "uploaded" -> "0", "downloaded" -> "0", "left" -> "1277987")
    val encodedParams = (for ((k, v) <- params) yield URLEncoder.encode(k) + "=" + URLEncoder.encode(v)).mkString("&")
    //  val encodedParams = URLEncoder.encode(infoSHAASCII, "UTF-8")
    //    println(s"${infoSHA} encoded to ${encodedParams} (${infoSHAASCII})")
    val infoSHAParam = s"info_hash=${infoSHAEncoded}"
    val peerIdParam = s"peer_id=${infoSHAEncoded}" //FIXME: peer id should obviously not be the same as our hash
    val allParams = s"?${infoSHAParam}&${peerIdParam}&${encodedParams}"

    val completeUrl = "http://thomasballinger.com:6969/announce" + allParams
    println(s"sending ${allParams}")
    //IP seems to be 67.215.65.132

    val url = new URL(completeUrl)
    val trackerResponse = fromInputStream(url.openStream, "macintosh").getLines.mkString("\n")

    //    println(content.split(":").last.toCharArray.map(_.toByte).mkString(",")) //this was a highly upvoted, but wrong, stackoverflow suggestion

    val decodedTrackerResponse = BencodeDecoder.decode(trackerResponse)
    println(trackerResponse)
    //    println(decodedTrackerResponse)

    val someTrackerResponse = decodedTrackerResponse.get match {
      case m: Map[String, String] => m
      case _ => throw new ClassCastException
    }

    //here, we see that 
    //    println(trackerResponse.split(":").last.getBytes.mkString(","))
    //    println(someTrackerResponse.get("peers").get.getBytes.mkString(",")) 

    val peers = someTrackerResponse.get("peers").get

    def toUnsignedByte(i: Int) = {
      if (i < 0)
        256 + i
      else
        i
    }

    def peersToIp(allPeers: String) = {
      val peers = allPeers.getBytes.grouped(6).toList.map(_.map(toUnsignedByte(_)))
      peers.foreach(x => println(x.mkString(".")))
      val ips = peers.map(x => x.slice(0, 4).mkString("."))
      val ports = peers.map{x => 
        println(s"port calculation: ${x(4)}, ${x(5)}, result = ${(x(4) << 4) + x(5)}")
        (x(4) << 8) + x(5)
      }
//      println(s"ips: ${ips}")
//      println(s"ports: ${ports}")
      ips zip ports
      //      (ips, ports)
    }

    val ipPorts = peersToIp(peers)

    ipPorts.foreach{p => 
      println(s"Connecting to ${p._1}:${p._2}")
      server ! TCPServer.ConnectToPeer(p._1, p._2, infoSHA)}

//    println(ipPorts.last)
//    server ! TCPServer.ConnectToPeer(ipPorts.last._1, ipPorts.last._2)
    system.scheduler.scheduleOnce(5.seconds) {system.shutdown()}

  }
}

class TCPServer() extends Actor with ActorLogging {
  import TCPServer._

  import scala.collection.mutable.Map

  val subservers = Map.empty[IO.Handle, ActorRef]
  val serverSocket = IOManager(context.system).listen("0.0.0.0", 31733)

  def receive = {
    //FIXME: only passing info_hash in because we're putting the handshake here
    case ConnectToPeer(ip, port, info_hash) =>
      val socket = IOManager(context.system) connect (ip, port) //Ip, port
      socket write ByteString("")
      subservers += (socket -> context.actorOf(Props(new SubServer(socket))))
      //FIXME: this handshake should probably live somewhere else
      val pstrlen: Array[Byte] = Array(19)
      val pstr = "BitTorrent protocol".getBytes
      val reserved: Array[Byte] = Array(0,0,0,0,0,0,0,0)
      //FIXME: peer_id should not be info_hash
      val handshake: Array[Byte]  = pstrlen ++ pstr ++ reserved ++ info_hash.getBytes ++ info_hash.getBytes 
//      println(s"Handshake: ${handshake.mkString(" ")}")
//      println(s"pstr ${pstr.mkString(" ")}")
//      println(s"reserved ${reserved.mkString(" ")}")
//      println(s"concat ${(pstr ++ reserved).mkString(" ")}")
      println(s"info_hash ${info_hash.getBytes.mkString(" ")}")
      val handshakeStr = (new String(handshake))
      val handshakeBS: akka.util.ByteString = akka.util.ByteString.fromArray(handshake, 0, handshake.length)
      println(s"HandBS  : ${handshakeBS}")
      socket write handshakeBS

    case IO.Listening(server, address) => log.info("TCP Server listeninig on port {}", address)
    case IO.NewClient(server) =>
      log.info("New incoming client connection on server")
      val socket = server.accept()
      //      socket.write(ByteString(welcome))
      subservers += (socket -> context.actorOf(Props(new SubServer(socket))))
    case IO.Read(socket, bytes) =>
      log.info(s"Read data from ${socket}")
      val cmd = ascii(bytes)
      subservers(socket) ! NewMessage(cmd)
    case IO.Closed(socket, cause) =>
      context.stop(subservers(socket))
      log.info(s"connection to ${socket} closed: ${cause}")
      subservers -= socket
  }
}

object TCPServer {
  implicit val askTimeout = Timeout(1.second)
  val welcome = "return message thingy"
  def ascii(bytes: ByteString): String = {
    bytes.decodeString("UTF-8").trim
  }

  case class NewMessage(msg: String)
  case class ConnectToPeer(ip: String, port: Int, info_hash: String)
  case class Handshake()

  class SubServer(socket: IO.SocketHandle) extends Actor {
    def receive = {
      case NewMessage(msg) =>
        msg match {
          case "heading" =>
          //            handleHeading()
          case "altitude" =>
          //            handleAltitude()
          case m =>
            socket.write(ByteString("What?\n\n"))
        }
    }
  }
}
