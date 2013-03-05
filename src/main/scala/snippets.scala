package org.storrent

import org.saunter.bencode._
import scalaj.http.Http
import collection.immutable.ListMap

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
      case m: Map[String,String] => m
      case _ => throw new ClassCastException
    }

    println(metaMap)

//    val trackerResponse = Http("http://thomasballinger.com:6969/announce").asString
//    println(trackerResponse)
  }
}
