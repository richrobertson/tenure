ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "com.richrobertson"
ThisBuild / version := "0.1.0-SNAPSHOT"

val catsEffectVersion = "3.5.7"
val http4sVersion = "0.23.30"
val circeVersion = "0.14.10"
val munitCatsEffectVersion = "2.0.0"

lazy val root = (project in file("."))
  .settings(
    name := "tenure",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "cats-effect-std" % catsEffectVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion % Test,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-generic-extras" % circeVersion,
      "org.typelevel" %% "munit-cats-effect-3" % munitCatsEffectVersion % Test
    ),
    Compile / run / fork := true,
    Test / fork := false
  )
