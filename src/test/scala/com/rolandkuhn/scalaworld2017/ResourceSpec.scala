package com.rolandkuhn.scalaworld2017

import org.scalatest.Matchers
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.refspec.RefSpec
import org.scalatest.BeforeAndAfterAll
import akka.typed.ActorSystem
import akka.typed.scaladsl.Actor

class ResourceSpec extends RefSpec with Matchers with TypeCheckedTripleEquals with BeforeAndAfterAll {

  val system = ActorSystem(Actor.empty, "ResourceSpec")

  override def afterAll(): Unit = {
    system.terminate()
  }

  object `A Resource` {

    def `must work`(): Unit = {
      println("it does!")
    }

  }

}
