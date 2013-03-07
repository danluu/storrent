package org.storrent

import org.saunter.bencode._
//import dispatch._
import collection.immutable.ListMap
import java.net.URLEncoder

import scala.io.Source.{fromInputStream}
import java.net._

import akka.actor.{Actor, ActorRef, IO, IOManager, ActorLogging, Props}
import akka.util.ByteString
import akka.pattern.ask
import akka.util._
import scala.concurrent.duration._
import scala.util.{Success, Failure}

import  scala.concurrent.ExecutionContext.Implicits.global
 

//org.apache.http <- Daniel S recommended this (just use the java one)
//import org.apache.http.client._
//import org.apache.http.client.methods._
//import org.apache.http.impl.client._

import scala.util.parsing.combinator._
import scala.util.parsing.input._

object Snippets {
  def main(args: Array[String]) {
    //    val metainfoStream  = Resource.fromFile("tom.torrent").mkString
    val source = scala.io.Source.fromFile("tom.torrent", "ISO-8859-1")
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
    val infoSHA = md.digest(encodedInfoMap.getBytes).map(0xFF & _).map { "%02x".format(_) }.foldLeft("") { _ + _ } //taken from Play

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
    val trackerResponse = fromInputStream( url.openStream, "ISO-8859-1").getLines.mkString("\n")

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
      val ips = peers.map(x => x.slice(0,4).mkString("."))
      val ports = peers.map(x => x.slice(4,6).map( m => 0xFF & m ).mkString(" "))
      println(s"ips: ${ips}")
      println(s"ports: ${ports}")
    }

    peersToIp(peers)




  }
}



class TCPServer() extends Actor with ActorLogging {
  import TCPServer._

 import scala.collection.mutable.Map

  case class PeerConnect(ip: String, port: Int)

  val subservers = Map.empty[IO.Handle, ActorRef]
  val serverSocket = IOManager(context.system).listen("0.0.0.0", 31733)

  def receive = {
    case PeerConnect(ip, port) =>
      val socket = IOManager(context.system) connect ("127.0.0.1", 0) //Ip, port
      socket write ByteString("")
      subservers += (socket -> context.actorOf(Props(new SubServer(socket))))

    case IO.Listening(server, address) => log.info("Telnet Server listeninig on port {}", address)
    case IO.NewClient(server) =>
      log.info("New incoming client connection on server")
      val socket = server.accept()
//      socket.write(ByteString(welcome))
      subservers += (socket -> context.actorOf(Props(new SubServer(socket))))
    case IO.Read(socket, bytes) =>
      val cmd = ascii(bytes)
      subservers(socket) ! NewMessage(cmd)
    case IO.Closed(socket, cause) =>
      context.stop(subservers(socket))
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
