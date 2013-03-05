package org.storrent

import org.saunter.bencode._
import scalaj.http.Http
import collection.immutable.ListMap
import java.net.URLEncoder

object Snippets{
  def main(args: Array[String]){
//    val metainfoStream  = Resource.fromFile("tom.torrent").mkString
    val source = scala.io.Source.fromFile("tom.torrent")
    val metainfo = source.mkString
    source.close()
    val decodedMeta = BencodeDecoder.decode(metainfo)
//    println(decodedMeta)

//    decodedMeta.get.foreach{x => println(s"ITEM: ${x}")}
    val metaMap = decodedMeta.get match {
      case m: Map[Any,Any] => m
      case _ => throw new ClassCastException
    }

    val infoMap = metaMap.get("info").get
    val encodedInfoMap = BencodeEncoder.encode(infoMap)
//    val encodedInfoMap = BencodeEncoder.encode(List("foo","bar"))
//    println(encodedInfoMap)

    val md = java.security.MessageDigest.getInstance("SHA-1")
    val infoSHA = md.digest(encodedInfoMap.getBytes).map(0xFF & _).map{ "%02x".format(_) }.foldLeft("") { _ + _ }

    val params = Map("info_hash"->infoSHA)

    val encodedParams = (for ((k, v) <- params) yield URLEncoder.encode(k) + "=" + URLEncoder.encode(v) ).mkString("&")
    println(s"${infoSHA} encoded to ${encodedParams}")

    //IP seems to be 67.215.65.132
//    val trackerResponse = Http("http://thomasballinger.com:6969/announce").asString
//    println(trackerResponse)
  }
}
