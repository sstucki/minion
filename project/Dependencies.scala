import sbt._

object Dependencies {

  /* Scala libraries */

  lazy val scalaTest    = "org.scalatest" %% "scalatest" % "3.0.5"
  lazy val scalaXml     = "org.scala-lang.modules" %% "scala-xml" % "1.0.6"
  // We don't need this since KeY comes with its own SMT-LIB interface.
  //lazy val scalaSmtLib = "com.regblanc" %% "scala-smtlib" % "0.2.2"
  lazy val scalaParsers =
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.1"

  /* Resolver for KeY components (bintray) */
  lazy val keyResolver = Resolver.bintrayRepo("key", "stable-snapshots")

  /* KeY components */

  // TODO: update to latest snapshot?

  //lazy val keyCore = "org.key_project" % "key.core" % "2.7.0-20190514"
  //lazy val keySymExec =
  //  "org.key_project" % "key.core.symbolic_execution" % "2.7.0-20190514"
  //lazy val keyUtils = "org.key_project" % "key.util" % "2.7.0-20190514"

  lazy val keyCore = "key-project" % "key.core" % "2.7.0.20190312"
  lazy val keySymExec =
    "key-project" % "key.core.symbolic_execution" % "2.7.0.20190312"
  lazy val keyUtil = "key-project" % "key.util" % "2.7.0.20190312"

  lazy val recoderKey = "key-project" % "recoderKey" % "1.0"
}
