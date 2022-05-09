import sbt._

object Dependencies {
  val scalaV = "2.13"

  val jacksonV = "2.13.2"
  val jacksonHotfixV = "2.13.2.2" // for when only some of the Jackson libs have hotfix releases
  val akkaV = "2.6.15"
  val akkaHttpV = "10.2.0"

  val workbenchModelV  = "0.15-808590d"
  val workbenchModel: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-model" % workbenchModelV
  val excludeWorkbenchModel = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-model_" + scalaV)

  val workbenchGoogleV = "0.21-808590d"
  val workbenchGoogle: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-google" % workbenchGoogleV excludeAll (excludeWorkbenchModel)
  val excludeWorkbenchGoogle = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-google_" + scalaV)

  val workbenchServiceTestV = "2.0-89b188f"
  val workbenchServiceTest: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-service-test" % workbenchServiceTestV % "test" classifier "tests" excludeAll (excludeWorkbenchGoogle, excludeWorkbenchModel)

  val rootDependencies = Seq(
    // proactively pull in latest versions of Jackson libs, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonV,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonHotfixV,
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonV,
    "com.fasterxml.jackson.module" % ("jackson-module-scala_" + scalaV) % jacksonV,
    "net.virtual-void" %% "json-lenses" % "0.6.2" % "test",
    "ch.qos.logback" % "logback-classic" % "1.2.11",
    "com.google.apis" % "google-api-services-oauth2" % "v1-rev127-1.22.0" excludeAll (
      ExclusionRule("com.google.guava", "guava-jdk5"),
      ExclusionRule("org.apache.httpcomponents", "httpclient")
    ),
    "com.google.api-client" % "google-api-client" % "1.22.0" excludeAll (
      ExclusionRule("com.google.guava", "guava-jdk5"),
      ExclusionRule("org.apache.httpcomponents", "httpclient")),
    "com.typesafe.akka"   %%  "akka-http-core"     % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-stream-testkit" % akkaV,
    "com.typesafe.akka"   %%  "akka-http"           % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-testkit"        % akkaV     % "test",
    "com.typesafe.akka"   %%  "akka-slf4j"          % akkaV,
    "org.specs2"          %%  "specs2-core"   % "4.15.0"  % "test",
    "org.scalatest"       %%  "scalatest"     % "3.2.12"   % Test,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",

    "net.logstash.logback" % "logstash-logback-encoder" % "6.6", // needed by workbench-google

    workbenchServiceTest,
    workbenchModel,
    workbenchGoogle
  )
}
