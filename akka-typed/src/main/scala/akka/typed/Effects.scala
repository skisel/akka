/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.typed

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec
import scala.collection.immutable

/**
 * All tracked effects must extend implement this type. It is deliberately
 * not sealed in order to allow extensions.
 */
abstract class Effect

object Effect {
  case class Spawned(childName: String) extends Effect
  case class Stopped(childName: String) extends Effect
  case class Watched[T](other: ActorRef[T]) extends Effect
  case class Unwatched[T](other: ActorRef[T]) extends Effect
  case class ReceiveTimeoutSet(d: Duration) extends Effect
  case class Messaged[U](other: ActorRef[U], msg: U) extends Effect
  case class Scheduled[U](delay: FiniteDuration, target: ActorRef[U], msg: U) extends Effect
  case object EmptyEffect extends Effect
}

/**
 * An [[ActorContext]] for testing purposes that records the effects performed
 * on it and otherwise stubs them out like a [[DummyActorContext]].
 */
class EffectfulActorContext[T](_name: String, _props: Props[T], _system: ActorSystem[Nothing])
  extends DummyActorContext[T](_name, _props)(_system) {
  import akka.{ actor ⇒ a }
  import Effect._

  private val eq = new ConcurrentLinkedQueue[Effect]
  def getEffect(): Effect = eq.poll() match {
    case null ⇒ throw new NoSuchElementException(s"polling on an empty effect queue: $name")
    case x    ⇒ x
  }
  def getAllEffects(): immutable.Seq[Effect] = {
    @tailrec def rec(acc: List[Effect]): List[Effect] = eq.poll() match {
      case null ⇒ acc.reverse
      case x    ⇒ rec(x :: acc)
    }
    rec(Nil)
  }
  def hasEffects: Boolean = eq.peek() != null

  private var current = props.creator()
  signal(PreStart)

  def currentBehavior: Behavior[T] = current

  def run(msg: T): Unit = current = Behavior.canonicalize(this, current.message(this, msg), current)
  def signal(signal: Signal): Unit = current = Behavior.canonicalize(this, current.management(this, signal), current)

  override def spawn[U](props: Props[U]): ActorRef[U] = {
    val ref = super.spawn(props)
    eq.offer(Spawned(ref.ref.path.name))
    ref
  }
  override def spawn[U](props: Props[U], name: String): ActorRef[U] = {
    eq.offer(Spawned(name))
    super.spawn(props, name)
  }
  override def actorOf(props: a.Props): a.ActorRef = {
    val ref = super.actorOf(props)
    eq.offer(Spawned(ref.path.name))
    ref
  }
  override def actorOf(props: a.Props, name: String): a.ActorRef = {
    eq.offer(Spawned(name))
    super.actorOf(props, name)
  }
  override def stop(childName: String): Unit = {
    eq.offer(Stopped(childName))
    super.stop(childName)
  }
  override def watch[U](other: ActorRef[U]): ActorRef[U] = {
    eq.offer(Watched(other))
    super.watch(other)
  }
  override def unwatch[U](other: ActorRef[U]): ActorRef[U] = {
    eq.offer(Unwatched(other))
    super.unwatch(other)
  }
  override def watch(other: akka.actor.ActorRef): other.type = {
    eq.offer(Watched(ActorRef[Any](other)))
    super.watch(other)
  }
  override def unwatch(other: akka.actor.ActorRef): other.type = {
    eq.offer(Unwatched(ActorRef[Any](other)))
    super.unwatch(other)
  }
  override def setReceiveTimeout(d: Duration): Unit = {
    eq.offer(ReceiveTimeoutSet(d))
    super.setReceiveTimeout(d)
  }
  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): a.Cancellable = {
    eq.offer(Scheduled(delay, target, msg))
    super.schedule(delay, target, msg)
  }
}
