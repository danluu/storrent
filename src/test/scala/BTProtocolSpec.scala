package org.storrent

import akka.actor.{ Actor, ActorSystem, Props}
import akka.testkit.{ TestActorRef, TestKit, TestLatch, ImplicitSender, TestProbe }
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.{ WordSpec, BeforeAndAfterAll}
import org.scalatest.matchers.MustMatchers
import akka.util.ByteString

class BTProtocolSpec extends TestKit(ActorSystem("BTProtocolSpec"))
with ImplicitSender
with WordSpec
with MustMatchers
with BeforeAndAfterAll {
  import BTProtocol._
  import Frame._

  object fakeTCPClient {
  }

  trait fakeTCPClient extends TCPClientProvider {
    def recieve = Actor.emptyBehavior
  }

  val fakePeerConnect = TestProbe()

  def slicedBTProtocol = new BTProtocol("", 0, fakePeerConnect.ref ,Array.fill(20){0}, 16384*10, 16384) with fakeTCPClient

  // FIXME: this code is here because the client only supports recieving, for now
  def createChokeFrame(): ByteString = {
    val headerLenB = intToByte(1, 4)
    val headerIdB = ByteString(0)
    headerLenB ++ headerIdB
  }

  def createUnchokeFrame(): ByteString = {
    val headerLenB = intToByte(1, 4)
    val headerIdB = ByteString(1)
    headerLenB ++ headerIdB
  }

  def createHaveFrame(piece: Int): ByteString = {
    val headerLenB = intToByte(5, 4)
    val headerIdB = ByteString(4)
    val pieceB = intToByte(piece, 4)

    headerLenB ++ headerIdB ++ pieceB
  }

  def createHandshakeFrame(): ByteString = {
    ByteString(Array.fill(68){0.toByte})
  }


  "BTProtocol" should {
    "choke" in {
      val a = TestActorRef[BTProtocol](Props(slicedBTProtocol))
      a ! TCPClient.DataReceived(createHandshakeFrame())
      a ! TCPClient.DataReceived(createChokeFrame())
      fakePeerConnect.expectMsg(Choke())
    }
    "unchoke" in {
      val a = TestActorRef[BTProtocol](Props(slicedBTProtocol))
      a ! TCPClient.DataReceived(createHandshakeFrame())
      a ! TCPClient.DataReceived(createUnchokeFrame())
      fakePeerConnect.expectMsg(Unchoke())
    }
    "have" in {
      val a = TestActorRef[BTProtocol](Props(slicedBTProtocol))
      a ! TCPClient.DataReceived(createHandshakeFrame())
      a ! TCPClient.DataReceived(createHaveFrame(1))
      fakePeerConnect.expectMsg(Have(1))
    }
  }
}



