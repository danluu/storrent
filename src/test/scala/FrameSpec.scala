package org.storrent

import org.scalatest._
import akka.util.ByteString

class FrameSpec extends FlatSpec with ShouldMatchers {
  import Frame._

  "parseFrame" should "parse short frames" in {
    parseFrame(intToByte(0, 4)) should equal ((4,None))
    parseFrame(ByteString(2)) should equal ((0,None))
    parseFrame(ByteString(2) ++ ByteString(11)) should equal ((0,None))
  }
}
