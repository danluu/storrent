package org.storrent

import org.saunter.bencode._
//import dispatch._
import collection.immutable.ListMap
import java.net.URLEncoder

import scala.io.Source.{fromInputStream}
import java.net._


//org.apache.http <- Daniel S recommended this (just use the java one)
//import org.apache.http.client._
//import org.apache.http.client.methods._
//import org.apache.http.impl.client._

import scala.util.parsing.combinator._
import scala.util.parsing.input._

object Snippets {
  def main(args: Array[String]) {
    //    val metainfoStream  = Resource.fromFile("tom.torrent").mkString
    val source = scala.io.Source.fromFile("tom.torrent")
    val metainfo = source.mkString
    source.close()
    val decodedMeta = BencodeDecoder.decode(metainfo)
//    println(s"decoded torrent ${decodedMeta}")

    //    decodedMeta.get.foreach{x => println(s"ITEM: ${x}")}
    val metaMap = decodedMeta.get match {
      case m: Map[Any, Any] => m
      case _ => throw new ClassCastException
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
    val trackerResponse = ""
    val debugPeer = trackerResponse.split(":").last


    println("decoding tracker response")
    val decodedTrackerResponse = BencodeDecoder.decode(trackerResponse)
    println(trackerResponse)
    println(URLEncoder.encode(trackerResponse))
    println(decodedTrackerResponse)

    println(debugPeer.length) //this result, direct from scalaj-http, is missing a char. Could this really be a scalaj-http bug?
    println(debugPeer.getBytes.mkString(",")) //this result, direct from scalaj-http, is missing a char. Could this really be a scalaj-http bug?
//    println(debugPeer(17))

    val url = new URL(completeUrl)
    val content = fromInputStream( url.openStream ).getLines.mkString("\n")
    println(debugPeer.getBytes.mkString(",")) //this result, direct from scalaj-http, is missing a char. Could this really be a scalaj-http bug?

    println(s"other method\n${content}")
    println(content.split(":").last.getBytes.mkString(","))
//    println(content.split(":").last.toCharArray.map(_.toByte).mkString(",")) //this was a highly upvoted, but wrong, stackoverflow suggestion

  }
}
