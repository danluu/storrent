package org.storrent

import akka.util.ByteString

object Frame {  
  def createInterestedFrame(): ByteString = {
    val msgAr: Array[Byte] = Array(0, 0, 0, 1, 2)
    ByteString.fromArray(msgAr, 0, msgAr.length)
  }

  def createPieceFrame(index: Int): ByteString = {
    //FIXME: this assumes the index < 256
    //FIXME: hardcoding length because we know the file has piece size 16384
    //      val indexBytes = java.nio.ByteBuffer.allocate(4)
    //      val aBytes: Array[Byte] = Array(indexBytes.putInt(index))
    val msgAr: Array[Byte] =
      Array(0, 0, 0, 13, //len
        6, //id
        0, 0, 0, index.toByte, //index
        0, 0, 0, 0, //begin
        0, 0, 0x40, 0) //length = 16384
    ByteString.fromArray(msgAr, 0, msgAr.length)
  }

  def createHandshakeFrame(info_hash: Array[Int]) = {
    val pstrlen: Array[Byte] = Array(19)
    val pstr = "BitTorrent protocol".getBytes
    val reserved: Array[Byte] = Array.fill(8){0}
    val info_hash_local: Array[Byte] = info_hash.map(_.toByte)
    val handshake: Array[Byte] = pstrlen ++ pstr ++ reserved ++ info_hash_local ++ info_hash_local //FIXME: peer_id should not be info_hash
    ByteString.fromArray(handshake, 0, handshake.length)
  }
}
