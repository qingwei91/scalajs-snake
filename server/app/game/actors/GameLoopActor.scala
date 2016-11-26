package game.actors

import akka.actor.{Actor, ActorRef, Props}
import shared.model.GameState

import scala.concurrent.duration._
import scala.language.postfixOps

class GameLoopActor(loopPerSec: Int, subscriberRef: ActorRef) extends Actor {
  private val gameStateRef = context.actorOf(GameStateActor.props)
  private lazy val millisPerUpdate = 1000 / loopPerSec

  gameStateRef ! InitState(GameState(Seq.empty, Set.empty))
  gameStateRef ! NextFrame

  override def receive: Receive = pendingResponse(System.currentTimeMillis())

  def pendingResponse(requestTime: Long): Receive = {
    case s: GameState =>
      subscriberRef ! s

      val millisToWait = timeToNextFrame(requestTime)

      assert(millisToWait > 0)

      println(s"Wait for : $millisToWait ms")
      context.system.scheduler.scheduleOnce(millisToWait millis, gameStateRef, NextFrame)(context.dispatcher)
      context.become(pendingResponse(System.currentTimeMillis()))
  }

  def timeToNextFrame(lastFrameMillis: Long): Long = {
    val now = System.currentTimeMillis()
    val millisToWait = millisPerUpdate - (now - lastFrameMillis)
    millisToWait
  }
}

object GameLoopActor {
  def props(updateRate: Int, subscribeRef: ActorRef): Props = Props(classOf[GameLoopActor], updateRate, subscribeRef)
}
