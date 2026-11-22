ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "0.1.0"

// An explicit root keeps sbt from synthesizing an aggregate project, so the
// lockfile has exactly the three modules named here.
lazy val root = (project in file("."))
  .aggregate(core, app)
  .settings(name := "example-sbt-multi")

lazy val core = (project in file("core"))
  .settings(
    name := "example-sbt-multi-core",
    libraryDependencies += "org.typelevel" %% "cats-core" % "2.13.0"
  )

// `app` exercises three things at once: an inter-module `dependsOn`, an sbt
// plugin (BuildInfoPlugin generates sources at build time, inside the
// sandbox), and a test scope.
lazy val app = (project in file("app"))
  .dependsOn(core)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "example-sbt-multi-app",
    Compile / mainClass := Some("app.Main"),
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "app.buildinfo",
    libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test
  )
