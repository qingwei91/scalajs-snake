package test

import java.nio.ByteBuffer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.TestKit
import controllers.Application
import org.scalatest._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import boopickle.Default._
import shared.protocol.{GameRequest, GameResponse, GameState, JoinGame}
import shared.serializers.Serializers._

import scala.language.postfixOps

class ApplicationControllerSpec extends TestKit(ActorSystem("Test")) with WordSpecLike with MustMatchers {
  implicit val materializer = ActorMaterializer()(system)
  implicit val ec: ExecutionContext = system.dispatcher

  "websocket endpoint " should {
    val controller = new Application()(system, materializer)

    "accept ws req" in {
      val flowToTest = controller.wsFlow("SSS")
      val (pub, sub) =
        TestSource.probe[Array[Byte]].via(flowToTest).toMat(TestSink.probe[Array[Byte]])(Keep.both).run()

      val joinGameBB = Pickle.intoBytes[GameRequest](JoinGame("test"))

      pub.sendNext(bbToArrayBytes(joinGameBB))

      sub.request(1)
      sub.expectNext(2 seconds)
    }

    "receive gameState continuously" in {
      val flowToTest = controller.wsFlow("SSS")

      val (pub, sub) = TestSource
        .probe[Array[Byte]]
        .via(flowToTest.map(bytes => {
          val bb = ByteBuffer.wrap(bytes)
          Unpickle[GameResponse].fromBytes(bb)
        }))
        .toMat(TestSink.probe[GameResponse])(Keep.both)
        .run()

      val joinGameBB = Pickle.intoBytes[GameRequest](JoinGame("test"))

      pub.sendNext(bbToArrayBytes(joinGameBB))

      sub.request(2)
      sub.expectNext()
      sub.expectNext()
    }
  }
}
