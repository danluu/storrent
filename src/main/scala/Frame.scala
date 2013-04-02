package org.storrent

import akka.util.ByteString
import scala.collection.mutable

object Frame {
  def createInterestedFrame(): ByteString = {
    val msgAr: Array[Byte] = Array(0, 0, 0, 1, 2)
    ByteString.fromArray(msgAr, 0, msgAr.length)
  }

  def createPieceFrame(index: Int, pieceLength: Long): ByteString = {
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
    val reserved: Array[Byte] = Array.fill(8) { 0 }
    val info_hash_local: Array[Byte] = info_hash.map(_.toByte)
    val handshake: Array[Byte] = pstrlen ++ pstr ++ reserved ++ info_hash_local ++ info_hash_local //FIXME: peer_id should not be info_hash
    ByteString.fromArray(handshake, 0, handshake.length)
  }

  // Determine if we have at least one entire message. Return number of bytes consumed
  def parseFrame(localBuffer: ByteString): (Int, Option[ByteString]) = {
    var message =
      if (localBuffer.length < 4) // can't decode frame length
        return (0, None)

    val length = BTProtocol.bytesToInt(localBuffer.take(4))
    if (length > localBuffer.length - 4) // incomplete frame
      return (0, None)

    if (length > 0) { // watch out for 0 length keep-alive message
      val message = localBuffer.drop(4).take(length)
      (length + 4, Some(message))
    } else {
      (4, None)
    }
  }

  // FIXME: either this should be in another class or the name of this class should be changed
  def bitfieldToSet(bitfield: ByteString, index: Int, hasPiece: mutable.Set[Int]): Unit = {
    // goes through each byte, and calls a function which goes through each bit and converts MSB:0 -> LSB:N in Set
    def byteToSet(byte: Byte, index: Int) = {
      def bitToSet(bit_index: Int): Unit = {
        if ((byte & (1 << bit_index)) != 0) {
          hasPiece += 8 * index + (7 - bit_index)
        }
        if (bit_index > 0) {
          bitToSet(bit_index - 1)
        }
      }
      bitToSet(7)
    }
    byteToSet(bitfield.drop(index)(0), index)

    val newIndex = index + 1
    if (newIndex < bitfield.length)
      bitfieldToSet(bitfield, newIndex, hasPiece)
  }
}
