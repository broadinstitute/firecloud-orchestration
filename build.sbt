name := "FireCloud-Orchestration"

organization := "org.broadinstitute.dsde.firecloud"

// Canonical version
val versionRoot = "0.1"

// Get the revision, or -1 (later will be bumped to zero)
val versionRevision = {
  try {
	("git rev-list --count HEAD" #|| "echo -1").!!.trim.toInt
  } catch {
    case e: Exception =>
      0
  }
}

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

scalaVersion  := "2.11.7"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8", "-target:jvm-1.8")

libraryDependencies ++= {
  val akkaV = "2.4.1"
  val sprayV = "1.3.3"
  val jacksonV = "2.8.4"
  Seq(
    // proactively pull in latest versions of Jackson libs, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonV,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonV,
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonV,
    "ch.qos.logback" % "logback-classic" % "1.1.3",
    "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2",
    "org.broadinstitute.dsde.vault" %%  "vault-common"  % "0.1-19-ca8b927",
    "io.spray"            %%  "spray-can"     % sprayV,
    "io.spray"            %%  "spray-routing" % sprayV,
    "io.spray"            %%  "spray-json"    % "1.3.2",
    "io.spray"            %%  "spray-client"  % sprayV,
    "io.spray"            %%  "spray-testkit" % sprayV    % "test",
    "org.webjars"          %  "swagger-ui"    % "2.2.5",
    "com.typesafe.akka"   %%  "akka-actor"    % akkaV,
    "com.typesafe.akka"   %%  "akka-contrib"  % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"  % akkaV     % "test",
    "com.typesafe.akka"   %%  "akka-slf4j"    % akkaV,
    "org.elasticsearch"    % "elasticsearch"  % "2.4.1",
    ("com.google.api-client" % "google-api-client" % "1.22.0").exclude("com.google.guava", "guava-jdk5"),
    "com.google.apis" % "google-api-services-storage" % "v1-rev58-1.21.0",
    "com.google.apis" % "google-api-services-compute" % "v1-rev120-1.22.0",
    "com.jason-goodwin"   %% "authentikat-jwt" % "0.4.1",
    "com.sun.mail"         % "javax.mail" % "1.5.6",
    "org.ocpsoft.prettytime" % "prettytime" % "4.0.1.Final",
    "org.everit.json"      %  "org.everit.json.schema" % "1.4.0",
    "org.specs2"          %%  "specs2-core"   % "3.7"  % "test",
    "org.scalatest"       %%  "scalatest"     % "2.2.6"   % "test",
    "org.mock-server"      %  "mockserver-netty" % "3.10.2" % "test"
  )
}

assemblyMergeStrategy in assembly := {
  case PathList("org", "joda", "time", "base", "BaseDateTime.class") => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
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

// Build without running tests.
test in assembly := {}
