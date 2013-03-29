package org.storrent

import akka.actor.{ Actor, ActorRef, Props, ActorSystem }

object Storrent {
  val system = ActorSystem("storrent")
  def main(args: Array[String]) {
    val fileName = "tom.torrent"
    system.actorOf(Props(new Torrent(fileName)), s"Torrent${fileName}")
  }
}
