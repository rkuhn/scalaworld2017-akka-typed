name := "scalaworld2017-akka-typed"
version := "0.1.0-SNAPSHOT"
organization := "com.rolandkuhn"
scalaVersion := "2.12.2"

scalacOptions += "-deprecation"
logBuffered in Test := false

val akkaVersion = "2.5.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-typed-testkit" % akkaVersion % "test",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test",
  "org.scalacheck" %% "scalacheck" % "1.13.4" % "test"
)
