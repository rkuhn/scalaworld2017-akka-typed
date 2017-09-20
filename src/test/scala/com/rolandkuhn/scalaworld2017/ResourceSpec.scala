package com.rolandkuhn.scalaworld2017

import org.scalatest.Matchers
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.refspec.RefSpec
import org.scalatest.BeforeAndAfterAll
import akka.typed.ActorSystem
import akka.typed.scaladsl.Actor
import akka.typed.testkit.EffectfulActorContext
import akka.typed.testkit.Inbox
import akka.typed.Behavior
import scala.concurrent.Future
import akka.Done

class ResourceSpec extends RefSpec with Matchers with TypeCheckedTripleEquals with BeforeAndAfterAll {

  val system = ActorSystem(Actor.empty, "ResourceSpec")

  override def afterAll(): Unit = {
    system.terminate()
  }

  object `A Resource` {

    val initial = ResourceActor.initial(Resource(_ => Future.successful(42), (_, _) => Future.successful(Done)))

    def `must work`(): Unit = {
      val inbox = Inbox[OpReply]("reply")
      run(initial, SingleRead("boo", inbox.ref)).currentBehavior
      inbox.receiveMsg() should ===(OpFailed("not cached"))
    }

    object `when knowing about foo` {

      val sessionInbox = Inbox[SessionReply]("session")
      val sessionCtx = run(initial, GetSession(sessionInbox.ref))

      val session = sessionInbox.receiveMsg() match {
        case SessionGranted(ref) => ref
      }

      val fooInbox = Inbox[OpReply]("foo")

      session ! Write("foo", 43, fooInbox.ref) // put Read into adapter
      sessionCtx.run(sessionCtx.selfInbox.receiveMsg()) // forward wrapped Read into actor

      Thread.sleep(500) // FIXME: needs control of ctx.executionContext

      sessionCtx.run(sessionCtx.selfInbox.receiveMsg()) // forward wrapped Result into actor
      fooInbox.receiveMsg() should ===(OpDone("foo", 43))

      def `must read back foo`(): Unit = {
        session ! Read("foo", fooInbox.ref)
        sessionCtx.run(sessionCtx.selfInbox.receiveMsg())
        fooInbox.receiveMsg() should ===(OpDone("foo", 43))
      }
    }

  }

  private def run[T](behavior: Behavior[T], msg: T): EffectfulActorContext[T] = {
    val ctx = new EffectfulActorContext("one", behavior, 1, system)
    ctx.run(msg)
    ctx
  }

}
