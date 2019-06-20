import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "se.gu",
      scalaVersion := "2.12.5",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "Minion",
    scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation"),
    javaSource in Test := baseDirectory.value / "examples",
    compileOrder in Test := CompileOrder.JavaThenScala
  )

libraryDependencies ++= Seq(
  scalaTest % Test,
  scalaXml,
  scalaParsers,
  //keyCore, keySymExec, keyUtils,
  keySymExec,
  recoderKey)

resolvers += keyResolver,  // for KeY components
