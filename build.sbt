import Settings._
import Testing._
import spray.revolver.RevolverPlugin

lazy val root = project.in(file("."))
  .settings(rootSettings:_*)
  .withTestSettings

enablePlugins(RevolverPlugin)

Revolver.enableDebugging(port = 5051, suspend = false)

// When JAVA_OPTS are specified in the environment, they are usually meant for the application
// itself rather than sbt, but they are not passed by default to the application, which is a forked
// process. This passes them through to the "re-start" command, which is probably what a developer
// would normally expect.
reStart / javaOptions ++= sys.env("JAVA_OPTS").split(" ").toSeq
