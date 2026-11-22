ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "example-sbt",
    libraryDependencies += "org.typelevel" %% "cats-core" % "2.13.0"
  )
