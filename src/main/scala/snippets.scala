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
  val blob = system.actorOf(Props(new BigFIXMEObject()), "BigFIXMEObject")
  def main(args: Array[String]) {
    blob ! BigFIXMEObject.DoEverything
    system.scheduler.scheduleOnce(20.seconds) { system.shutdown() }
  }
}

object BigFIXMEObject {
  case class DoEverything
  case class HashRequest //    val metainfoStream  = Resource.fromFile("tom.torrent").mkString

}

class BigFIXMEObject extends Actor with ActorLogging {
  import BigFIXMEObject._
  val server = context.actorOf(Props(new TCPServer()), "TCPServer")

  def receive = {
    case DoEverything =>

      val source = scala.io.Source.fromFile("tom.torrent", "macintosh")
      val metainfo = source.mkString
      source.close()
      val decodedMeta = BencodeDecoder.decode(metainfo)

//      println(s"decoded torrent ${decodedMeta}")

      //    decodedMeta.get.foreach{x => println(s"ITEM: ${x}")}
      val metaMap = decodedMeta.get match {
        case m: Map[String, Any] => m
        case m => println(m.getClass.getSimpleName); throw new ClassCastException
      } 

      val infoMap = metaMap.get("info").get  match{
        case m: Map[String,Any] => m
        case m => println(m.getClass.getSimpleName); throw new ClassCastException
      }
      val encodedInfoMap = BencodeEncoder.encode(infoMap)

      //    val encodedInfoMap = BencodeEncoder.encode(List("foo","bar"))
      //    println(encodedInfoMap)
      val fileLength = infoMap.get("length").get match {
        case m: Long => m
        case m => println(m.getClass.getSimpleName); throw new ClassCastException
      }
      val pieceLength = infoMap.get("piece length").get match {
        case m: Long => m
        case m => println(m.getClass.getSimpleName); throw new ClassCastException
      }
//       println(infoMap.get("piece length").get)
      val sparePiece = fileLength % pieceLength match {
        case 0 => 0
        case _ => 1
      }
      val numPieces = fileLength / pieceLength + sparePiece

      println(s"numPieces: ${numPieces}")

      val md = java.security.MessageDigest.getInstance("SHA-1")
      val infoSHABytes = md.digest(encodedInfoMap.getBytes).map(0xFF & _)
      val infoSHA = infoSHABytes.map { "%02x".format(_) }.foldLeft("") { _ + _ } //taken from Play
//      println(s"hash into string: ${infoSHA}")

      //take a string that's already in hex and URLEncode it by putting a % in front of each pair
      def hexStringURLEncode(x: String) = {
        x.grouped(2).toList.map("%" + _).mkString("")
      }

      val infoSHAEncoded = hexStringURLEncode(infoSHA)
//      println(infoSHAEncoded)

      val params = Map("port" -> "63211", "uploaded" -> "0", "downloaded" -> "0", "left" -> "1277987")
      val encodedParams = (for ((k, v) <- params) yield URLEncoder.encode(k) + "=" + URLEncoder.encode(v)).mkString("&")
      //  val encodedParams = URLEncoder.encode(infoSHAASCII, "UTF-8")
      //    println(s"${infoSHA} encoded to ${encodedParams} (${infoSHAASCII})")
      val infoSHAParam = s"info_hash=${infoSHAEncoded}"
      val peerIdParam = s"peer_id=${infoSHAEncoded}" //FIXME: peer id should obviously not be the same as our hash
      val allParams = s"?${infoSHAParam}&${peerIdParam}&${encodedParams}"

      val completeUrl = "http://thomasballinger.com:6969/announce" + allParams
//      println(s"sending ${allParams}")
      //IP seems to be 67.215.65.132

      val url = new URL(completeUrl)
      val trackerResponse = fromInputStream(url.openStream, "macintosh").getLines.mkString("\n")

      //    println(content.split(":").last.toCharArray.map(_.toByte).mkString(",")) //this was a highly upvoted, but wrong, stackoverflow suggestion

      val decodedTrackerResponse = BencodeDecoder.decode(trackerResponse)
//      println(trackerResponse)
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
        val ports = peers.map { x =>
//          println(s"port calculation: ${x(4)}, ${x(5)}, result = ${(x(4) << 4) + x(5)}")
          (x(4) << 8) + x(5)
        }
        //      println(s"ips: ${ips}")
        //      println(s"ports: ${ports}")
        ips zip ports
        //      (ips, ports)
      }

      val ipPorts = peersToIp(peers)

      ipPorts.foreach { p =>
        println(s"Connecting to ${p._1}:${p._2}")
        server ! TCPServer.ConnectToPeer(p._1, p._2, infoSHABytes, fileLength, pieceLength)
      }

    //    println(ipPorts.last)
    //    server ! TCPServer.ConnectToPeer(ipPorts.last._1, ipPorts.last._2)

  }

}

class TCPServer() extends Actor with ActorLogging {
  import TCPServer._

  import scala.collection.mutable.Map

  val subservers = Map.empty[IO.Handle, ActorRef]
  val handshakeSeen = Map.empty[IO.Handle, Boolean]
  val hasPiece = Map.empty[IO.Handle, scala.collection.mutable.Set[Int]] //inefficient representation
  val weHavePiece = Map.empty[IO.Handle, scala.collection.mutable.Set[Int]]
  //FIXME: need a way to specify that we're currently downloading and should not request again

  val serverSocket = IOManager(context.system).listen("0.0.0.0", 31733)

  def receive = {
    //FIXME: only passing info_hash in because we're putting the handshake here
    case ConnectToPeer(ip, port, info_hash, fileLength, pieceLength) =>
      val socket = IOManager(context.system) connect (ip, port) //Ip, port
      socket write ByteString("") //FIXME: what is this for? This can't be needed
      subservers += (socket -> context.actorOf(Props(new SubServer(socket))))
      hasPiece += (socket -> scala.collection.mutable.Set())
      weHavePiece += (socket -> scala.collection.mutable.Set())
      //FIXME: this handshake should probably live somewhere else
      val pstrlen: Array[Byte] = Array(19)
      val pstr = "BitTorrent protocol".getBytes
      val reserved: Array[Byte] = Array(0, 0, 0, 0, 0, 0, 0, 0)
      //FIXME: peer_id should not be info_hash
      val info_hash_local: Array[Byte] = info_hash.map(_.toByte)
      val handshake: Array[Byte] = pstrlen ++ pstr ++ reserved ++ info_hash_local ++ info_hash_local
      //      println(s"Handshake: ${handshake.mkString(" ")}")
      //      println(s"pstr ${pstr.mkString(" ")}")
      //      println(s"reserved ${reserved.mkString(" ")}")
      //      println(s"concat ${(pstr ++ reserved).mkString(" ")}")
      //      println(s"info_hash ${info_hash_local.mkString(" ")}")
      val handshakeStr = (new String(handshake))
      val handshakeBS: akka.util.ByteString = akka.util.ByteString.fromArray(handshake, 0, handshake.length)
      //      println(s"HandBS  : ${handshakeBS}")
      socket write handshakeBS

    case IO.Listening(server, address) => log.info("TCP Server listeninig on port {}", address)
    case IO.NewClient(server) =>
      log.info("New incoming client connection on server")
      val socket = server.accept()
      //      socket.write(ByteString(welcome))
      subservers += (socket -> context.actorOf(Props(new SubServer(socket))))
    case IO.Read(socket, bytes) =>
//      log.info(s"Read data from ${socket}: ${bytes} (${bytes.length})")
      var bytesRead = 0
      if (! handshakeSeen.isDefinedAt(socket)){
        if (bytes.length < 68)
          throw new Exception("Only received part of a packet")
        handshakeSeen += (socket -> true)
        bytesRead += 68
        subservers(socket) ! SendInterested
      }
//      val cmd = ascii(bytes)
      if (bytes.length > bytesRead){
        readMessage()
      }



      //FIXME: this should use a fold and be general
      def fourBytesToInt(bytes: IndexedSeq[Byte]): Int = {
         (bytes(0) << 8*3) + (bytes(1) << 8*2) + (bytes(2) << 8) + bytes(3)
      }

      //WARNING: we're assuming that IO.read always returns complete messages. We'll get an exception here from the take if that's false
      def readMessage(): Unit = {
        val lengthBytes = bytes.drop(bytesRead).take(4)
//        val length = (lengthBytes(0) << 8*3) + (lengthBytes(1) << 8*2) + (lengthBytes(2) << 8) + lengthBytes(3) //FIXME: fold this
        val length = fourBytesToInt(lengthBytes)
        bytesRead += 4
        val message = bytes.drop(bytesRead).take(length)
        bytesRead += length

//        println(s"Received message: ${message} (${length})")
        def processMessage(m: ByteString){
          val rest = m.drop(1)
          m(0) & 0xFF match {
            case 1 => //UNCHOKE
              println("UNCHOKE")
              val missing: Set[Int] = (hasPiece(socket).toSet) -- (weHavePiece(socket).toSet)
              subservers(socket) ! GetPiece(missing.head)
            case 4 =>  //HAVE piece
              val index = fourBytesToInt(rest.take(4))
//              println(s"HAVE ${index}")
              val newSet = (hasPiece.get(socket).get) += index
            case 5 => //BITFIELD
//              println(s"BITFIELD")
              val pieceSet = hasPiece.get(socket).get
              def bitfieldToSet(index: Int): Unit = {
                //goes through each byte, and calls a function which goes through each bit and converts MSB:0 -> LSB:N in Set
                def byteToSet(byte: Byte, index: Int) = {
                  def bitToSet(bit_index: Int): Unit = {
//                    println(s"bitToSet with bit_index ${bit_index}. byte: ${byte} & ${1 << bit_index} = ${byte & (1 << bit_index)}")
                    if ((byte & (1 << bit_index)) != 0){
//                      println(s"adding ${8 * index + (7 - bit_index)}")
                      pieceSet += 8 * index + (7 - bit_index)
                    }
                    if (bit_index > 0){
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

              println(s"peiceSet: ${pieceSet}")
            case 7 => //PEICE
              println(s"PEICE ${rest.take(4)}")
              val index = fourBytesToInt(rest.take(4))
              val oldSet = (weHavePiece.get(socket).get)
              oldSet += index
              val missing = hasPiece(socket) -- weHavePiece(socket)
              subservers(socket) ! GetPiece(missing.head)
          }
        }

        processMessage(message)

        if (bytes.length > bytesRead){
          readMessage()
        }
      }
      


//      log.info(s"Read data from ${socket}: ${cmd}")
//      subservers(socket) ! NewMessage(cmd)
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
        if (! interested){
          println("Sending Interested message")
          val msgAr: Array[Byte] = Array(0,0,0,1,2)
          val msg: ByteString = akka.util.ByteString.fromArray(msgAr, 0, msgAr.length)
          socket.write(msg)
        }
      case GetPiece(index) =>
          //FIXME: this assumes the index < 256
          //FIXME: hardcoding length because we know the file has piece size 16384
        val msgAr: Array[Byte] =
          Array(0,0,0,13, //len
            6, //id
            0,0,0,index.toByte, //index
            0,0,0,0, //begin
            0,0,0x40,0) //length = 16384
        val msg = akka.util.ByteString.fromArray(msgAr, 0, msgAr.length)
        println(s"sending request for piece: ${msg}")
        socket.write(msg)

    }
  }
}
