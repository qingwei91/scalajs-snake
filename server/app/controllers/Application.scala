package controllers

import java.nio.ByteBuffer

import akka.NotUsed
import akka.actor._
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl._
import game.actors._
import play.api.Logger
import play.api.mvc.{Action, Controller, WebSocket}
import shared.core.IdentifiedGameInput
import shared.model._
import shared.protocol.{GameRequest, JoinGame, LeaveGame}
import shared.serializers.Serializers._
import boopickle.Default._
import shared.physics.PhysicsFormula

import scala.util.Random

class Application()(implicit actorSystem: ActorSystem, materializer: Materializer) extends Controller {

  val log = Logger(getClass)
  val gameState = actorSystem.actorOf(GameStateActor.props)

  val testState = {
    val snakes = for {
      i <- 1 to 3
    } yield {
      val blocks = PhysicsFormula.findContiguousBlock(shared.terrainX, shared.terrainY)
      Snake(Random.nextInt().toString, blocks, Up)
    }
    GameState(snakes, Set.empty)
  }
  gameState ! InitState(testState)

  lazy val playersState = actorSystem.actorOf(PlayersActor.props(shared.serverUpdateRate, gameState))

  def index = Action {
    Ok(views.html.main("Snake")(views.html.canvas()))
  }

  // TODO: check id to ensure unique
  def gameChannel(id: String) = WebSocket.accept[Array[Byte], Array[Byte]] { req =>
    wsFlow(id)
  }

  def test = WebSocket.accept[Array[Byte], Array[Byte]] { req =>
    wsFlow("Test")
  }

  def wsFlow(id: String): Flow[Array[Byte], Array[Byte], NotUsed] = {
    val deserializeState: Flow[Array[Byte], IdentifiedGameInput, NotUsed] =
      Flow.fromFunction[Array[Byte], IdentifiedGameInput] { rawBytes =>
        val r = Unpickle[GameRequest]
          .fromBytes(ByteBuffer.wrap(rawBytes))
        IdentifiedGameInput(id, r.cmd)
      }

    val serializeState: Flow[GameState, Array[Byte], NotUsed] =
      Flow.fromFunction[GameState, Array[Byte]] { st =>
        bbToArrayBytes(Pickle.intoBytes[GameState](st))
      }

    val coreLogicFlow = {
      val out =
        Source
          .actorRef(1000000, OverflowStrategy.dropNew)
          .mapMaterializedValue(ref => playersState ! PlayerJoin(id, ref))

      val in = Sink.actorRef(playersState, PlayerLeft(id))

      Flow.fromSinkAndSource(in, out)
    }

    deserializeState.via(coreLogicFlow).via(serializeState)
  }
}
