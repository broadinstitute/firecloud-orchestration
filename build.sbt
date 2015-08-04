name := "FireCloud-Orchestration"

val vaultOrg = "org.broadinstitute.dsde.vault"

organization  := vaultOrg

// Canonical version
val versionRoot = "0.1"

// Get the revision, or -1 (later will be bumped to zero)
val versionRevision = ("git rev-list --count HEAD" #|| "echo -1").!!.trim.toInt

// Set the suffix to None...
val versionSuffix = {
  try {
    // ...except when there are no modifications...
    if ("git diff --quiet HEAD".! == 0) {
      // ...then set the suffix to the revision "dash" git hash
      Option(versionRevision + "-" + "git rev-parse --short HEAD".!!.trim)
    } else {
      None
    }
  } catch {
    case e: Exception =>
      None
  }
}

// Set the composite version
version := versionRoot + "-" + versionSuffix.getOrElse((versionRevision + 1) + "-SNAPSHOT")

val artifactory = "https://artifactory.broadinstitute.org/artifactory/"

resolvers += "artifactory-releases" at artifactory + "libs-release"

scalaVersion  := "2.11.6"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-target:jvm-1.8")

libraryDependencies ++= {
  val akkaV = "2.3.9"
  val sprayV = "1.3.3"
  Seq(
    vaultOrg              %%  "vault-common"  % "0.1-17-0ee4ad5",
    "io.spray"            %%  "spray-can"     % sprayV,
    "io.spray"            %%  "spray-routing" % sprayV,
    "io.spray"            %%  "spray-json"    % "1.3.1",
    "io.spray"            %%  "spray-client"  % sprayV,
    "io.spray"            %%  "spray-testkit" % sprayV    % "test",
    "com.gettyimages"     %%  "spray-swagger" % "0.5.0",
    "org.webjars"          %  "swagger-ui"    % "2.1.0",
    "com.typesafe.akka"   %%  "akka-actor"    % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"  % akkaV     % "test",
    "com.typesafe.akka"   %%  "akka-slf4j"    % akkaV,
    "org.specs2"          %%  "specs2-core"   % "2.3.11"  % "test",
    "org.scalatest"       %%  "scalatest"     % "2.2.1"   % "test",
    "org.mock-server"      %  "mockserver-netty" % "3.9.2" % "test"
    // -- Logging --
    ,"ch.qos.logback" % "logback-classic" % "1.1.2"
    ,"com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2"
  )
}

Revolver.settings.settings

Revolver.enableDebugging(port = 5051, suspend = false)

javaOptions in Revolver.reStart += "-Dconfig.file=src/main/resources/application.conf"

// SLF4J initializes itself upon the first logging call.  Because sbt
// runs tests in parallel it is likely that a second thread will
// invoke a second logging call before SLF4J has completed
// initialization from the first thread's logging call, leading to
// these messages:
//   SLF4J: The following loggers will not work because they were created
//   SLF4J: during the default configuration phase of the underlying logging system.
//   SLF4J: See also http://www.slf4j.org/codes.html#substituteLogger
//   SLF4J: com.imageworks.common.concurrent.SingleThreadInfiniteLoopRunner
//
// As a workaround, load SLF4J's root logger before starting the unit
// tests

// Source: https://github.com/typesafehub/scalalogging/issues/23#issuecomment-17359537
// References:
//   http://stackoverflow.com/a/12095245
//   http://jira.qos.ch/browse/SLF4J-167
//   http://jira.qos.ch/browse/SLF4J-97

parallelExecution in Test := false

testOptions in Test += Tests.Setup(classLoader =>
  classLoader
    .loadClass("org.slf4j.LoggerFactory")
    .getMethod("getLogger", classLoader.loadClass("java.lang.String"))
    .invoke(null, "ROOT")
)

// Uncomment to build without running tests.
// test in assembly := {}
