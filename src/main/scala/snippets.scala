package org.storrent

import org.saunter.bencode._
import scalaj.http.Http
import collection.immutable.ListMap
import java.net.URLEncoder

import scala.util.parsing.combinator._
import scala.util.parsing.input._

object Snippets {
  def main(args: Array[String]) {
    //    val metainfoStream  = Resource.fromFile("tom.torrent").mkString
    val source = scala.io.Source.fromFile("tom.torrent")
    val metainfo = source.mkString
    source.close()
    val decodedMeta = BencodeDecoder.decode(metainfo)
    println(s"decoded torrent ${decodedMeta}")

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

    println(s"sending ${allParams}")
    //IP seems to be 67.215.65.132
    val trackerResponse = Http("http://thomasballinger.com:6969/announce" + allParams).asString
    //    val trackerResponse = Http("http://thomasballinger.com:6969/announce").asString
    val decodedTrackerResponse = BencodeDecoder.decode(trackerResponse)
    println(trackerResponse)
    println(decodedTrackerResponse)
  }
}
