package org.storrent

import akka.actor.{ Actor, ActorRef, Props, ActorSystem }

object Storrent {
  val system = ActorSystem("storrent")
  def main(args: Array[String]) {
    if(args.isEmpty) {
      println("Usage: Storrent [torrent file] [torrent file] ...")
    } else {
      args.foreach { f => system.actorOf(Props(new Torrent(f)), s"Torrent${f}") }
    }
  }
}
