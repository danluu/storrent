package org.storrent

import org.saunter.bencode._

object Snippets{
  def main(args: Array[String]){
//    val metainfoStream  = Resource.fromFile("tom.torrent").mkString
    val source = scala.io.Source.fromFile("tom.torrent")
    val metainfo = source.mkString
    source.close()
    val decodedMeta = BencodeDecoder.decode(metainfo)
    println(decodedMeta)
  }
}
