import sbt._

object Dependencies {
  val scalaV = "2.13"

  val jacksonV = "2.17.1"
  val jacksonHotfixV = "2.17.1" // for when only some of the Jackson libs have hotfix releases
  val akkaV = "2.6.19"
  val akkaHttpV = "10.2.10"
  val workbenchLibsHash = "3e0cf25"

  val workbenchModelV  = s"0.20-$workbenchLibsHash"
  val workbenchModel: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-model" % workbenchModelV
  val excludeWorkbenchModel = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-model_" + scalaV)

  val workbenchGoogleV = s"0.33-$workbenchLibsHash"
  val workbenchGoogle: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-google" % workbenchGoogleV excludeAll excludeWorkbenchModel
  val excludeWorkbenchGoogle = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-google_" + scalaV)

  val workbenchServiceTestV = s"5.0-$workbenchLibsHash"
  val workbenchServiceTest: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-service-test" % workbenchServiceTestV % "test" classifier "tests" excludeAll (excludeWorkbenchGoogle, excludeWorkbenchModel)

  // Overrides for transitive dependencies. These apply - via Settings.scala - to all projects in this codebase.
  // These are overrides only; if the direct dependencies stop including any of these, they will not be included
  // by being listed here.
  // One reason to specify an override here is to avoid static-analysis security warnings.
  val transitiveDependencyOverrides: Seq[ModuleID] = Seq(
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonV,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonHotfixV,
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonV,
    "io.grpc" % "grpc-xds" % "1.56.1",
    "org.typelevel" %% "cats-effect" % "3.4.11",
    "org.typelevel" %% "cats-core" % "2.10.0"
  )

  val rootDependencies: Seq[ModuleID] = Seq(
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonV,
    "net.virtual-void" %% "json-lenses" % "0.6.2" % "test",
    "ch.qos.logback" % "logback-classic" % "1.5.11",
    "com.typesafe.akka"   %%  "akka-http-core"     % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-stream-testkit" % akkaV,
    "com.typesafe.akka"   %%  "akka-http"           % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-testkit"        % akkaV     % "test",
    "com.typesafe.akka"   %%  "akka-slf4j"          % akkaV,
    "org.specs2"          %%  "specs2-core"   % "4.15.0"  % "test",
    "org.scalatest"       %%  "scalatest"     % "3.2.19"   % Test,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",

    // required but not provided by workbench-google.
    // workbench-google specifies 7.0.1
    "net.logstash.logback" % "logstash-logback-encoder" % "7.1.1",

    workbenchServiceTest,
    workbenchModel,
    workbenchGoogle
  )
}
