package org.storrent

import akka.actor.{ Actor, ActorRef, IO, IOManager, ActorLogging, Props }
import akka.util.ByteString
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.Await

object TCPClient {
  case class DataReceived(buffer: ByteString)
  case class ConnectionClosed
  case class CloseConnection
  case class SendData(bytes: ByteString)
}

class TCPClient(ip: String, port: Int, peer: ActorRef) extends Actor with ActorLogging {
  import TCPClient._

  implicit val timeout = Timeout(5.seconds)
  val socket = IOManager(context.system) connect (ip, port) //Ip, port
  var buffer: ByteString = akka.util.ByteString()

  def receive = {
    case IO.Closed(socket, cause) =>
      log.info(s"connection to ${socket} closed: ${cause}")
      peer ! ConnectionClosed
      socket.close
    case IO.Read(_, bytes) =>
      buffer = buffer ++ bytes
      var bytesRead = 0
      do {
        bytesRead = Await.result(peer ? DataReceived(buffer), 5.seconds).asInstanceOf[Int]
        buffer = buffer.drop(bytesRead)
      } while (bytesRead > 0)
    case SendData(bytes) =>
      socket write bytes
    case CloseConnection =>
      peer ! ConnectionClosed
      socket.close
  }
}
