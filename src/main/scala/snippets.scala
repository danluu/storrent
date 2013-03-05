package org.storrent

import org.saunter.bencode._
import scalaj.http.Http
import collection.immutable.ListMap
import java.net.URLEncoder

object Snippets {
  def main(args: Array[String]) {
    //    val metainfoStream  = Resource.fromFile("tom.torrent").mkString
    val source = scala.io.Source.fromFile("tom.torrent")
    val metainfo = source.mkString
    source.close()
    val decodedMeta = BencodeDecoder.decode(metainfo)
    //    println(decodedMeta)

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

    /*
    def toAscii(hex: String) = {
      import javax.xml.bind.DatatypeConverter
      new String(DatatypeConverter.parseHexBinary(hex))
    }
 */

/*
    def toAscii(hex: String) = {
      require(hex.size % 2 == 0,
        "Hex must have an even number of characters. You had " + hex.size)
      val sb = new StringBuilder
      for (i <- 0 until hex.size by 2) {
        val str = hex.substring(i, i + 2)
        sb.append(Integer.parseInt(str, 16).toChar)

        val convert = Integer.parseInt(str,16).toChar
//        println(s"${str} converts to ${convert} encodes to ${URLEncoder.encode(convert.toString())}")
      }
      sb.toString
    }
 */

/*
    def wtfAscii() = {
      for (i <- 0 until 255){
        val str = i.toString()
        val convert = Integer.parseInt(str,16).toChar
        println(s"${str} converts to ${convert} encodes to ${URLEncoder.encode(convert.toString())}")
      }
    }
    wtfAscii()

 */
    val infoSHAASCII = toAscii(infoSHA)

    //    val params = Map("info_hash"->infoSHA)
    //    val encodedParams = (for ((k, v) <- params) yield URLEncoder.encode(k) + "=" + URLEncoder.encode(v) ).mkString("&")
    val encodedParams = URLEncoder.encode(infoSHAASCII, "UTF-8")
    println(s"${infoSHA} encoded to ${encodedParams} (${infoSHAASCII})")

    //IP seems to be 67.215.65.132
    //    val trackerResponse = Http("http://thomasballinger.com:6969/announce").asString
    //    println(trackerResponse)
  }
}
