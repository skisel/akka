/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.typed

import scala.annotation.tailrec
import Behavior._

/**
 * This object holds several behavior factories and combinators that can be
 * used to construct Behavior instances.
 */
object ScalaDSL {

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior. This is provided in order to
   * avoid the allocation overhead of recreating the current behavior where
   * that is not necessary.
   */
  def Same[T]: Behavior[T] = sameBehavior.asInstanceOf[Behavior[T]]

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior, including the hint that the
   * message has not been handled. This hint may be used by composite
   * behaviors that delegate (partial) handling to other behaviors.
   */
  def Unhandled[T]: Behavior[T] = unhandledBehavior.asInstanceOf[Behavior[T]]

  /*
   * TODO write a Behavior that waits for all child actors to stop and then
   * runs some cleanup before stopping. The factory for this behavior should
   * stop and watch all children to get the process started.
   */

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure. The PostStop
   * signal that results from stopping this actor will NOT be passed to the
   * current behavior, it will be effectively ignored. In order to install a
   * cleanup action please refer to
   * [[akka.typed.Behavior$.Stopped[T](cleanup* Stopped(cleanup: () => Unit)]].
   */
  def Stopped[T]: Behavior[T] = stoppedBehavior.asInstanceOf[Behavior[T]]

  /**
   * This behavior does not handle any inputs, it is completely inert.
   */
  def Empty[T]: Behavior[T] = emptyBehavior.asInstanceOf[Behavior[T]]

  /**
   * This behavior does not handle any inputs, it is completely inert.
   */
  def Ignore[T]: Behavior[T] = ignoreBehavior.asInstanceOf[Behavior[T]]

  /**
   * Algebraic Data Type modeling either a [[ScalaDSL$.Msg message]] or a
   * [[ScalaDSL$.Sig signal]], including the [[ActorContext]]. This type is
   * used by several of the behaviors defined in this DSL, see for example
   * [[ScalaDSL$.Full]].
   */
  sealed trait MessageOrSignal[T]
  /**
   * A message bundled together with the current [[ActorContext]].
   */
  case class Msg[T](ctx: ActorContext[T], msg: T) extends MessageOrSignal[T]
  /**
   * A signal bundled together with the current [[ActorContext]].
   */
  case class Sig[T](ctx: ActorContext[T], signal: Signal) extends MessageOrSignal[T]

  /**
   * This type of behavior allows to handle all incoming messages within
   * the same user-provided partial function, be that a user message or a system
   * signal. For messages that do not match the partial function the same
   * behavior is emitted without change. This does entail that unhandled
   * failures of child actors will lead to a failure in this actor.
   *
   * For the lifecycle notifications pertaining to the actor itself this
   * behavior includes a fallback mechanism: an unhandled [[PreRestart]] signal
   * will terminate all child actors (transitively) and then emit a [[PostStop]]
   * signal in addition, whereas an unhandled [[PostRestart]] signal will emit
   * an additional [[PreStart]] signal.
   */
  case class Full[T](behavior: PartialFunction[MessageOrSignal[T], Behavior[T]]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      lazy val fallback: (MessageOrSignal[T]) ⇒ Behavior[T] = {
        case Sig(context, PreRestart(_)) ⇒
          context.children foreach { child ⇒
            context.unwatch(child.ref)
            context.stop(child.path.name)
          }
          behavior.applyOrElse(Sig(context, PostStop), fallback)
        case Sig(context, PostRestart(_)) ⇒ behavior.applyOrElse(Sig(context, PreStart), fallback)
        case _                            ⇒ Unhandled
      }
      behavior.applyOrElse(Sig(ctx, msg), fallback)
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      behavior.applyOrElse(Msg(ctx, msg), unhandledFunction)
    }
    override def toString = s"Full(${LN.forClass(behavior.getClass)})"
  }

  /**
   * This type of behavior expects a total function that describes the actor’s
   * reaction to all system signals or user messages, without providing a
   * fallback mechanism for either. If you use partial function literal syntax
   * to create the supplied function then any message not matching the list of
   * cases will fail this actor with a [[scala.MatchError]].
   */
  case class FullTotal[T](behavior: MessageOrSignal[T] ⇒ Behavior[T]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal) = behavior(Sig(ctx, msg))
    override def message(ctx: ActorContext[T], msg: T) = behavior(Msg(ctx, msg))
    override def toString = s"FullTotal(${LN.forClass(behavior.getClass)})"
  }

  /**
   * This type of behavior is created from a total function from the declared
   * message type to the next behavior, which means that all possible incoming
   * messages for the given type must be handled. All system signals are
   * ignored by this behavior, which implies that a failure of a child actor
   * will be escalated unconditionally.
   *
   * This behavior type is most useful for leaf actors that do not create child
   * actors themselves.
   */
  case class Total[T](behavior: T ⇒ Behavior[T]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = msg match {
      case _ ⇒ Unhandled
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = behavior(msg)
    override def toString = s"Simple(${LN.forClass(behavior.getClass)})"
  }

  /**
   * This type of Behavior is created from a partial function from the declared
   * message type to the next behavior, flagging all unmatched messages as
   * [[Behavior$.Unhandled]]. All system signals are
   * ignored by this behavior, which implies that a failure of a child actor
   * will be escalated unconditionally.
   *
   * This behavior type is most useful for leaf actors that do not create child
   * actors themselves.
   */
  case class Partial[T](behavior: PartialFunction[T, Behavior[T]]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = msg match {
      case _ ⇒ Unhandled
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = behavior.applyOrElse(msg, unhandledFunction)
    override def toString = s"Partial(${LN.forClass(behavior.getClass)})"
  }

  /**
   * This type of Behavior wraps another Behavior while allowing you to perform
   * some action upon each received message or signal. It is most commonly used
   * for logging or tracing what a certain Actor does.
   */
  case class Tap[T](f: PartialFunction[MessageOrSignal[T], Unit], behavior: Behavior[T]) extends Behavior[T] {
    private def canonical(behv: Behavior[T]): Behavior[T] =
      if (isUnhandled(behv)) Unhandled
      else if (behv eq sameBehavior) Same
      else if (isAlive(behv)) Tap(f, behv)
      else Stopped
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      f.applyOrElse(Sig(ctx, msg), unitFunction)
      canonical(behavior.management(ctx, msg))
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      f.applyOrElse(Msg(ctx, msg), unitFunction)
      canonical(behavior.message(ctx, msg))
    }
    override def toString = s"Tap(${LN.forClass(f.getClass)},$behavior)"
  }
  object Tap {
    def monitor[T](monitor: ActorRef[T], behavior: Behavior[T]): Tap[T] = Tap({ case Msg(_, msg) ⇒ monitor ! msg }, behavior)
  }

  /**
   * This type of behavior is a variant of [[Behavior.Simple]] that does not
   * allow the actor to change behavior. It is an efficient choice for stateless
   * actors, possibly entering such a behavior after finishing its
   * initialization (which may be modeled using any of the other behavior types).
   *
   * This behavior type is most useful for leaf actors that do not create child
   * actors themselves.
   */
  case class Static[T](behavior: T ⇒ Unit) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = msg match {
      case _ ⇒ Unhandled
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      behavior(msg)
      this
    }
    override def toString = s"Static(${LN.forClass(behavior.getClass)})"
  }

  /**
   * This behavior allows sending messages to itself without going through the
   * Actor’s mailbox. A message sent like this will be processed before the next
   * message is taken out of the mailbox. In case of Actor failures outstanding
   * messages that were sent to the synchronous self reference will be lost.
   *
   * This decorator is useful for passing messages between the left and right
   * sides of [[ScalaDSL$.And]] and [[ScalaDSL$.Or]] combinators.
   */
  case class SynchronousSelf[T](f: ActorRef[T] ⇒ Behavior[T]) extends Behavior[T] {
    private val inbox = Inbox.sync[T]("syncbox")
    private var _behavior = f(inbox.ref)
    private def behavior = _behavior
    private def setBehavior(ctx: ActorContext[T], b: Behavior[T]): Unit =
      _behavior = canonicalize(ctx, b, _behavior)

    @tailrec private def run(ctx: ActorContext[T], next: Behavior[T]): Behavior[T] = {
      setBehavior(ctx, next)
      if (inbox.hasMessages) run(ctx, behavior.message(ctx, inbox.receiveMsg()))
      else if (isUnhandled(next)) Unhandled
      else if (isAlive(next)) this
      else Stopped
    }

    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] =
      run(ctx, behavior.management(ctx, msg))
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] =
      run(ctx, behavior.message(ctx, msg))

    override def toString: String = s"SynchronousSelf($behavior)"
  }

  /**
   * A behavior combinator that feeds incoming messages and signals both into
   * the left and right sub-behavior and allows them to evolve independently of
   * each other. When one of the sub-behaviors terminates the other takes over
   * exclusively. When both sub-behaviors respond to a [[Failed]] signal, the
   * response with the higher precedence is chosen (see [[Failed$]]).
   */
  case class And[T](left: Behavior[T], right: Behavior[T]) extends Behavior[T] {

    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      val l = left.management(ctx, msg)
      val r = right.management(ctx, msg)
      if (isUnhandled(l) && isUnhandled(r)) Unhandled
      else {
        val nextLeft = canonicalize(ctx, l, left)
        val nextRight = canonicalize(ctx, r, right)
        val leftAlive = isAlive(nextLeft)
        val rightAlive = isAlive(nextRight)

        if (leftAlive && rightAlive) And(nextLeft, nextRight)
        else if (leftAlive) nextLeft
        else if (rightAlive) nextRight
        else Stopped
      }
    }

    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      val l = left.message(ctx, msg)
      val r = right.message(ctx, msg)
      if (isUnhandled(l) && isUnhandled(r)) Unhandled
      else {
        val nextLeft = canonicalize(ctx, l, left)
        val nextRight = canonicalize(ctx, r, right)
        val leftAlive = isAlive(nextLeft)
        val rightAlive = isAlive(nextRight)

        if (leftAlive && rightAlive) And(nextLeft, nextRight)
        else if (leftAlive) nextLeft
        else if (rightAlive) nextRight
        else Stopped
      }
    }
  }

  /**
   * A behavior combinator that feeds incoming messages and signals either into
   * the left or right sub-behavior and allows them to evolve independently of
   * each other. The message or signal is passed first into the left sub-behavior
   * and only if that results in [[Behavior$.Unhandled]] is it passed to the right
   * sub-behavior. When one of the sub-behaviors terminates the other takes over
   * exclusively. When both sub-behaviors respond to a [[Failed]] signal, the
   * response with the higher precedence is chosen (see [[Failed$]]).
   */
  case class Or[T](left: Behavior[T], right: Behavior[T]) extends Behavior[T] {

    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] =
      left.management(ctx, msg) match {
        case b if isUnhandled(b) ⇒
          val r = right.management(ctx, msg)
          if (isUnhandled(r)) Unhandled
          else {
            val nr = canonicalize(ctx, r, right)
            if (isAlive(nr)) Or(left, nr) else left
          }
        case nl ⇒
          val next = canonicalize(ctx, nl, left)
          if (isAlive(next)) Or(next, right) else right
      }

    override def message(ctx: ActorContext[T], msg: T): Behavior[T] =
      left.message(ctx, msg) match {
        case b if isUnhandled(b) ⇒
          val r = right.message(ctx, msg)
          if (isUnhandled(r)) Unhandled
          else {
            val nr = canonicalize(ctx, r, right)
            if (isAlive(nr)) Or(left, nr) else left
          }
        case nl ⇒
          val next = canonicalize(ctx, nl, left)
          if (isAlive(next)) Or(next, right) else right
      }
  }

  // TODO
  // case class Selective[T](timeout: FiniteDuration, selector: PartialFunction[T, Behavior[T]], onTimeout: () ⇒ Behavior[T])

  /**
   * A behavior decorator that extracts the self [[ActorRef]] while receiving the
   * the first signal or message and uses that to construct the real behavior
   * (which will then also receive that signal or message).
   *
   * Example:
   * {{{
   * SelfAware[MyCommand] { self =>
   *   Simple {
   *     case cmd =>
   *   }
   * }
   * }}}
   *
   * This can also be used together with implicitly sender-capturing message
   * types:
   * {{{
   * case class OtherMsg(msg: String)(implicit val replyTo: ActorRef[Reply])
   *
   * SelfAware[MyCommand] { implicit self =>
   *   Simple {
   *     case cmd =>
   *       other ! OtherMsg("hello") // assuming Reply <: MyCommand
   *   }
   * }
   * }}}
   */
  def SelfAware[T](behavior: ActorRef[T] ⇒ Behavior[T]): Behavior[T] =
    FullTotal {
      case Sig(ctx, signal) ⇒
        val behv = behavior(ctx.self)
        canonicalize(ctx, behv.management(ctx, signal), behv)
      case Msg(ctx, msg) ⇒
        val behv = behavior(ctx.self)
        canonicalize(ctx, behv.message(ctx, msg), behv)
    }

  /**
   * A behavior decorator that extracts the [[ActorContext]] while receiving the
   * the first signal or message and uses that to construct the real behavior
   * (which will then also receive that signal or message).
   *
   * Example:
   * {{{
   * ContextAware[MyCommand] { ctx => Simple {
   *     case cmd =>
   *       ...
   *   }
   * }
   * }}}
   */
  def ContextAware[T](behavior: ActorContext[T] ⇒ Behavior[T]): Behavior[T] =
    FullTotal {
      case Sig(ctx, signal) ⇒
        val behv = behavior(ctx)
        canonicalize(ctx, behv.management(ctx, signal), behv)
      case Msg(ctx, msg) ⇒
        val behv = behavior(ctx)
        canonicalize(ctx, behv.message(ctx, msg), behv)
    }

  /**
   * INTERNAL API.
   */
  private[akka] val _unhandledFunction = (_: Any) ⇒ Unhandled[Nothing]
  /**
   * INTERNAL API.
   */
  private[akka] def unhandledFunction[T, U] = _unhandledFunction.asInstanceOf[(T ⇒ Behavior[U])]

  /**
   * INTERNAL API.
   */
  private[akka] val _unitFunction = (_: Any) ⇒ ()
  /**
   * INTERNAL API.
   */
  private[akka] def unitFunction[T, U] = _unhandledFunction.asInstanceOf[(T ⇒ Behavior[U])]

}