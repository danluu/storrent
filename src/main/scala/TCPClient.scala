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
  def apply(ip: String, port: Int, btProtocol: ActorRef) = new TCPClient(ip: String, port: Int, btProtocol: ActorRef)
}

// FIXME: we may want to buffer more stuff in BTProtocol. "because buffering is part of parsing, not part of IO"
// alternately, we should look at length directly, right here. BTProtocol can look at fully frame messages only
class TCPClient(ip: String, port: Int, btProtocol: ActorRef) extends Actor with ActorLogging {
  import TCPClient._
  import Frame._

  implicit val timeout = Timeout(5.seconds)
  val socket = IOManager(context.system) connect (ip, port) //Ip, port
  var buffer: ByteString = akka.util.ByteString()

  def receive = {
    case IO.Closed(socket, cause) =>
      log.info(s"connection to ${socket} closed: ${cause}")
      btProtocol ! ConnectionClosed
      socket.close
    case IO.Read(_, bytes) =>
      buffer = buffer ++ bytes
      var bytesRead = 0
      do {
        bytesRead = parseFrame(buffer) match {
          case (0, _) => 0
          case (n, None) => n // this case can happen (keep-alive message)
          case (n, Some(m)) => btProtocol ! DataReceived(m); n
        }
        buffer = buffer.drop(bytesRead)
      } while (bytesRead > 0)
    case SendData(bytes) =>
      socket write bytes
    case CloseConnection =>
      btProtocol ! ConnectionClosed
      socket.close
  }
}

trait TCPClientProvider{
  def newTCPClient(ip: String, port: Int, peer: ActorRef): Actor = TCPClient(ip, port, peer)
}
