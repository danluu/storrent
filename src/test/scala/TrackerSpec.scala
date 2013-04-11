package org.storrent

import org.scalatest._

class TrackerSpec extends FlatSpec with ShouldMatchers {
  import Tracker._

  "getTorrentFileVariables" should "decode Map" in {
    getTorrentFileVariables(Map("length" -> 10.toLong, "piece length" -> 2.toLong)) should equal((10.toLong, 2.toLong, 4.toLong))
    getTorrentFileVariables(Map("length" -> 11.toLong, "piece length" -> 2.toLong)) should equal((11.toLong, 2.toLong, 5.toLong))
  }

}
