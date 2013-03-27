package org.storrent

import akka.actor.{ Actor, ActorRef, Props, ActorSystem }

object Snippets {
  val system = ActorSystem("storrent")
  def main(args: Array[String]) {
    val blob = system.actorOf(Props(new Torrent("tom.torrent")), "Torrent_tom.torrent")
  }
}

