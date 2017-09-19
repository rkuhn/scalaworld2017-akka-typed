package com.rolandkuhn.scalaworld2017

import scala.concurrent.Future
import akka.Done
import akka.typed.{ ActorRef, Behavior }
import akka.typed.scaladsl.Actor
import scala.collection.immutable.Queue
import scala.util.{ Success, Failure }

/*
 * Some generic resource that allows storing and retrieving things.
 * We can construct instances of this resource from two functions, which we
 * will use for testing purposes.
 */

trait Resource[In, Out] {
  def read(i: In): Future[Out]
  def write(i: In, o: Out): Future[Done]
}

object Resource {
  private def fail(msg: String) = Future.failed(new RuntimeException(msg))
  private val f1 = (_: Any) => fail("cannot read this resource")
  private val f2 = (_: Any, _: Any) => fail("cannot write this resource")

  def apply[In, Out](read: In => Future[Out] = f1,
                     write: (In, Out) => Future[Done] = f2): Resource[In, Out] = {
    val r = read
    val w = write
    new Resource[In, Out] {
      def read(i: In) = r(i)
      def write(i: In, o: Out) = w(i, o)
    }
  }
}

/*
 * We model a resource that can be queried with strings and returns numbers.
 * Every operation must be part of a session, with a simplified API for
 * single queries. Sessions are ensured to not overlap.
 */

sealed trait ResourceOp
case class GetSession(replyTo: ActorRef[SessionReply]) extends ResourceOp
case class SingleRead(item: String, replyTo: ActorRef[Either[String, OpReply]]) extends ResourceOp

sealed trait SessionReply
case class SessionGranted(session: ActorRef[SessionOp]) extends SessionReply
case class SessionDenied(errorMessage: String) extends SessionReply

sealed trait SessionOp
case class Read(item: String, replyTo: ActorRef[Either[String, OpReply]]) extends SessionOp
case class Write(item: String, value: BigDecimal, replyTo: ActorRef[Either[String, OpReply]]) extends SessionOp
case class EndSession(replyTo: ActorRef[SessionEnded.type]) extends SessionOp

case class OpReply(item: String, value: BigDecimal)
case object SessionEnded

/*
 * The Actor
 */

object ResourceActor {
  type Res = Resource[String, BigDecimal]
  type Cache = Map[String, BigDecimal]

  def initial(res: Res) = idle(res, Map.empty, 0)

  private case class SessionWrapper(snr: Int, op: SessionOp) extends ResourceOp
  private case class Result(result: Either[String, OpReply], snr: Int, replyTo: ActorRef[Either[String, OpReply]]) extends ResourceOp

  private def idle(res: Res, cache: Cache, snr: Int) =
    Actor.immutable[ResourceOp] { (ctx, op) =>
      op match {

        case GetSession(replyTo) =>
          // starting a session while idle will always succeed
          val session = ctx.spawnAdapter((op: SessionOp) => SessionWrapper(snr, op))
          replyTo ! SessionGranted(session)
          inSession(res, cache, snr, session, Queue.empty, busy = false)

        case SingleRead(item, replyTo) =>
          // a SingleRead is only served from the cache
          if (cache.contains(item)) {
            replyTo ! Right(OpReply(item, cache(item)))
            Actor.same
          } else {
            replyTo ! Left("not cached")
            Actor.same
          }

        // if queries or results from a previous session arrive, ignore them
        case SessionWrapper(_, _) => Actor.same
        case Result(_, _, _)      => Actor.same
      }
    }

  private def inSession(res: Res,
                        cache: Cache,
                        snr: Int,
                        session: ActorRef[SessionOp],
                        queue: Queue[GetSession],
                        busy: Boolean): Behavior[ResourceOp] =
    Actor.immutable { (ctx, op) =>
      import ctx.executionContext
      op match {

        case gs @ GetSession(replyTo) =>
          // only permit up to 16 queued GetSession requests, deny further ones
          if (queue.size > 15) {
            replyTo ! SessionDenied("too many sessions waiting")
            Actor.same
          } else {
            inSession(res, cache, snr, session, queue :+ gs, busy)
          }

        case SingleRead(item, replyTo) =>
          // a SingleRead is only served from the cache
          if (cache.contains(item)) {
            replyTo ! Right(OpReply(item, cache(item)))
            Actor.same
          } else {
            replyTo ! Left("not cached")
            Actor.same
          }

        case SessionWrapper(session, _) if snr != session =>
          Actor.same // ignore; sender will get Terminated for their session eventually

        case SessionWrapper(_, sop) =>
          // handle queries from the current session
          sop match {

            case Read(item, replyTo) =>
              // permit reads only while not awaiting a result
              if (busy) {
                replyTo ! Left("must await finish of current operation")
                Actor.same
              } else if (cache.contains(item)) {
                replyTo ! Right(OpReply(item, cache(item)))
                Actor.same
              } else {
                res.read(item).transform {
                  case Success(value) => Success(Right(OpReply(item, value)))
                  case Failure(ex)    => Success(Left(ex.getMessage))
                }.foreach(v => ctx.self ! Result(v, snr, replyTo))
                // transition to “busy” mode
                inSession(res, cache, snr, session, queue, busy = true)
              }

            case Write(item, value, replyTo) =>
              // permit writes only while not awaiting a result
              if (busy) {
                replyTo ! Left("must await finish of current operation")
                Actor.same
              } else {
                res.write(item, value).transform {
                  case Success(_)  => Success(Right(OpReply(item, value)))
                  case Failure(ex) => Success(Left(ex.getMessage))
                }.foreach(v => ctx.self ! Result(v, snr, replyTo))
                // transition into “busy” mode
                inSession(res, cache, snr, session, queue, busy = true)
              }

            case EndSession(replyTo) =>
              // end this session upon the client’s request; timeouts are not yet implemented
              ctx.stop(session)
              replyTo ! SessionEnded
              if (queue.nonEmpty) {
                val (next, rest) = queue.dequeue
                val session = ctx.spawnAdapter((op: SessionOp) => SessionWrapper(snr, op))
                next.replyTo ! SessionGranted(session)
                inSession(res, cache, snr + 1, session, rest, busy = false)
              } else {
                idle(res, cache, snr + 1)
              }
          }

        case Result(value, sessnr, replyTo) =>
          // handle a result for the current session (ignore stragglers from previous sessions)
          if (sessnr == snr) {
            replyTo ! value
            // update the cache when a value is available
            val c = value match {
              case Left(_)                     => cache
              case Right(OpReply(item, value)) => cache.updated(item, value)
            }
            inSession(res, c, snr, session, queue, busy = false)
          } else Actor.same
      }
    }

}
