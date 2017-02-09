import Settings._
import Testing._
import spray.revolver.RevolverPlugin.Revolver


lazy val root = project.in(file("."))
  .settings(rootSettings:_*)
  .withTestSettings

Revolver.settings.settings

Revolver.enableDebugging(port = 5051, suspend = false)

// When JAVA_OPTS are specified in the environment, they are usually meant for the application
// itself rather than sbt, but they are not passed by default to the application, which is a forked
// process. This passes them through to the "re-start" command, which is probably what a developer
// would normally expect.
javaOptions in Revolver.reStart ++= sys.env("JAVA_OPTS").split(" ").toSeq

