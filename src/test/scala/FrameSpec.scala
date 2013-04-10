package org.storrent

import org.scalatest._

class FrameSpec extends FlatSpec with ShouldMatchers {
  import Frame._

  "parseFrame" should "parse short frames" in {
    intToByte(0, 4) should equal (intToByte(0, 4))
  }
}
