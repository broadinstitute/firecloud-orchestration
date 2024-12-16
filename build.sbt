import Settings.*
import Testing.*
import pl.project13.scala.sbt.JmhPlugin
import spray.revolver.RevolverPlugin

lazy val root = project.in(file("."))
  .settings(rootSettings *)
  .withTestSettings

enablePlugins(RevolverPlugin)

// JMH subproject for benchmarking
lazy val bench = project.in(file("benchmarks"))
  .dependsOn(root % "compile->compile")
  .disablePlugins(RevolverPlugin)
  .enablePlugins(JmhPlugin)
  .settings(
    scalaVersion  := (root / scalaVersion).value,
    // rewire tasks, so that 'bench/Jmh/run' automatically invokes 'bench/Jmh/compile'
    // and 'bench/Jmh/compile' invokes root's 'compile'
    Jmh / compile := (Jmh / compile).dependsOn(Compile / compile).value,
    Jmh / run := (Jmh / run).dependsOn(Jmh / compile).evaluated
  )


Revolver.enableDebugging(port = 5051, suspend = false)

// When JAVA_OPTS are specified in the environment, they are usually meant for the application
// itself rather than sbt, but they are not passed by default to the application, which is a forked
// process. This passes them through to the "re-start" command, which is probably what a developer
// would normally expect.
reStart / javaOptions ++= sys.env("JAVA_OPTS").split(" ").toSeq
