package shared.core

import shared.model._
import shared._
import shared.physics.{AABB, PhysicsFormula, Vec2}
import shared.protocol._

//TODO: figure out how to compile loop while maintain readability
object GameLogic {

  private def debuff(state: GameState): GameState = {
    val debuffed = state.snakes.map(s =>
      if (s.speedBuff.frameLeft > 0) s.copy(speedBuff = SpeedBuff(s.speedBuff.frameLeft - 1)) else s)
    state.copy(debuffed)
  }

  private def removeDeadSnakes(state: GameState): GameState = {
    val survivedSnakes = state.snakes.filterNot(snake => {
      val others     = state.snakes
      val targetHead = snake.body.head

      others.exists(s => s != snake && s.body.tail.exists(targetHead.collided))
    })

    state.copy(snakes = survivedSnakes)
  }

  private def removeEatenApple(state: GameState): GameState = {
    val snakeApple = for {
      s <- state.snakes
      a <- state.apples
      if s.body.exists(_.collided(a.position))
    } yield {
      (s, a)
    }

    val (snakesAteApple, appleEaten) = snakeApple.unzip

    val updatedSnake = state.snakes.map {
      case s if snakesAteApple.exists(_.id == s.id) =>
        val last2            = s.body.takeRight(2)
        val secondLastCenter = last2(1).center
        val diff             = secondLastCenter - last2.head.center
        val appended         = s.body :+ AABB(secondLastCenter + diff, last2(1).halfExtents)
        s.copy(body = appended, energy = Math.min(s.energy + 1, 5))
      case x => x
    }

    val appleNotEaten = state.apples.filterNot(a => appleEaten.contains(a))
    state.copy(updatedSnake, appleNotEaten)
  }

  private def updateSnakeByID(snakes: Seq[Snake], id: String)(update: Snake => Snake) = {
    snakes.map {
      case targeted if targeted.id == id =>
        update(targeted)
      case other => other
    }
  }

  def applyInput(state: GameState, inputs: Seq[IdentifiedGameInput]): GameState = {
//    inputs.collect { case IdentifiedGameInput(_, s: SequencedGameRequest) => s }.foreach(s => {
//      if (s.seqNo != state.seqNo) {
//        println(s"Input $s does not match ${state.seqNo}")
//      }
//    })

    val updatedState = inputs.foldLeft(state) {
      case (s, IdentifiedGameInput(id, ChangeDirection(dir, _))) =>
        val updatedSnakes = updateSnakeByID(s.snakes, id) { target =>
          if (target.direction.isOppositeOf(dir))
            target
          else
            target.copy(direction = dir)
        }
        s.copy(snakes = updatedSnakes)

      case (s, IdentifiedGameInput(id, SpeedUp(_))) =>
        val updatedSnakes = updateSnakeByID(s.snakes, id) { target =>
          if (target.energy > 0)
            target.copy(speedBuff = SpeedBuff(fps), energy = target.energy - 1)
          else
            target
        }
        s.copy(snakes = updatedSnakes)

      case (s, IdentifiedGameInput(id, JoinGame(name))) =>
        if (s.snakes.exists(_.id == id))
          s
        else {
          val emptyBlock = PhysicsFormula.findContiguousBlock(s, snakeBodyInitLength)
          val newSnake   = Snake(id, name, emptyBlock, Up)
          s.copy(snakes = s.snakes :+ newSnake)
        }

      case (s, IdentifiedGameInput(id, LeaveGame)) =>
        s.copy(snakes = s.snakes.filter(_.id != id))
    }

    updatedState
  }

  private def roundingBack(position: Vec2, boundary: (Double, Double)): Vec2 = (position, boundary) match {
    case (Vec2(x, y), (xMax, yMax)) =>
      val adjustedX = (if (x < 0) x + xMax else x) % xMax
      val adjustedY = (if (y < 0) y + yMax else y) % yMax
      Vec2(adjustedX, adjustedY)
  }

  private def applyMovement(state: GameState): GameState = {
    val moved = state.snakes.map(snake => {
      val diffBetweenElements =
        for {
          i <- 1 until snake.body.size
        } yield {
          val front: Vec2 = snake.body(i - 1).center
          val back: Vec2  = snake.body(i).center

          val diff = {
            (front - back).map(v =>
              Math.abs(v) match {
                case abs if abs > terrainX / 2 =>
                  abs / -v
                case x => v
            })
          }

//          assert(Math.abs(diff.magnitude) <= 50, s"Distance between snake body is too long: ${snake.name} ${snake.body}")

          diff
        }

      val movePerFrame = if (snake.speedBuff.frameLeft > 0) 1.5 * distancePerFrame else distancePerFrame

      val movedHead = {
        val moveStep = unitPerDirection(snake.direction) * movePerFrame
        val h        = snake.body.head
        h.copy(center = h.center + moveStep)
      }

      val movedTail = snake.body.tail.zip(diffBetweenElements).map {
        case (ele, vec) => ele.copy(ele.center + (vec * movePerFrame))
      }

      val movedBody: Seq[AABB] = (movedHead +: movedTail) map {
        case aabb @ AABB(center, _) => aabb.copy(center = roundingBack(center, (terrainX, terrainY)))
      }

      snake.copy(body = movedBody)
    })
    state.copy(snakes = moved)
  }

  private def replenishApple(state: GameState): GameState = {
    val diff = state.snakes.size - state.apples.size

    val apples = for {
      _ <- 0 to diff
    } yield {
      Apple(PhysicsFormula.findContiguousBlock(state, 1, state.seqNo).head)
    }

    state.copy(apples = state.apples ++ apples.toSet)
  }

  // assuming all is valid
  def step(state: GameState, inputs: Seq[IdentifiedGameInput]): (GameState, Option[GameStateDelta]) = {
//    val mismatch = inputs.collectFirst {
//      case IdentifiedGameInput(_, s: SequencedGameRequest) if s.seqNo != state.seqNo + 1 =>
//        s.seqNo
//    }
//    assert(
//      mismatch.isEmpty,
//      s"input seq mismatch: Expect ${state.seqNo + 1} got ${mismatch.get}"
//    )

    val allSteps =
      (removeDeadSnakes _)
        .andThen(debuff)
        .andThen(removeEatenApple)
        .andThen(s => applyInput(s, inputs))
        .andThen(applyMovement)
        .andThen(replenishApple)

    val delta = if (inputs.isEmpty) None else Some(GameStateDelta(inputs, state.seqNo + 1))

    val result = allSteps(state).increaseSeqNo

//    if (inputs.nonEmpty) {
//      println(s"In: ${state.seqNo}")
//      println(s"out: ${result.seqNo}")
//      println(s"Del: ${delta.map(_.seqNo)}")
//    }

    (result, delta)
  }
}
