import sbt._

object Dependencies {
  val akkaV = "2.9.3"
  val akkaHttpV = "10.6.3"
  val jacksonV = "2.19.2"
  val jacksonHotfixV = "2.19.2" // for when only some of the Jackson libs have hotfix releases
  val workbenchLibsHash = "9df42f5" // see https://github.com/broadinstitute/workbench-libs readme for hash values

  val excludeAkkaActor = ExclusionRule(organization = "com.typesafe.akka", name = "akka-actor_2.13")
  val excludeAkkaStream = ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.13")
  val excludeAkkaHttp = ExclusionRule(organization = "com.typesafe.akka", name = "akka-http_2.13")
  val excludeSprayJson = ExclusionRule(organization = "com.typesafe.akka", name = "akka-http-spray-json_2.13")

  val excludeSpring = ExclusionRule(organization = "org.springframework")
  val excludeSpringBoot = ExclusionRule(organization = "org.springframework.boot")
  val excludeSpringJcl = ExclusionRule(organization = "org.springframework", name = "spring-jcl")

  // Overrides for transitive dependencies. These apply - via Settings.scala - to all projects in this codebase.
  // These are overrides only; if the direct dependencies stop including any of these, they will not be included
  // by being listed here.
  // One reason to specify an override here is to avoid static-analysis security warnings.
  val transitiveDependencyOverrides: Seq[ModuleID] = Seq(
    "com.google.guava" % "guava" % "33.4.8-jre",
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonV,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonHotfixV,
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonV,
    "org.yaml" % "snakeyaml" % "2.5",
    "org.apache.commons" % "commons-compress" % "1.28.0", // workbench-libs libraries pull this in
    "com.google.apis" % "google-api-services-pubsub" % "v1-rev20250414-2.0.0", // from workbench-google2
    "com.google.apis" % "google-api-services-admin-directory" % "directory_v1-rev20250707-2.0.0" // from workbench-google2
  )

  val rootDependencies: Seq[ModuleID] = Seq(
    "ch.qos.logback" % "logback-classic" % "1.5.18",
    "io.sentry" % "sentry-logback" % "8.18.0",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
    "org.parboiled" % "parboiled-core" % "1.4.1",
    "org.broadinstitute.dsde" %% "rawls-model" % "v0.0.537-SNAP"
      exclude ("com.typesafe.scala-logging", "scala-logging_2.13")
      exclude ("com.typesafe.akka", "akka-stream_2.13")
      exclude ("com.google.code.findbugs", "jsr305")
      exclude ("org.typelevel", "cats-parse_2.13")
      excludeAll (excludeAkkaHttp, excludeSprayJson),
    "org.broadinstitute.dsde.workbench" %% "workbench-util" % s"0.10-$workbenchLibsHash",
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % s"0.40-$workbenchLibsHash"
    // we don't need all the libraries that workbench-google2 pulls in
    exclude ("com.google.cloud", "google-cloud-bigquery")
      exclude ("com.google.cloud", "google-cloud-billing")
      exclude ("com.google.cloud", "google-cloud-container")
      exclude ("com.google.cloud", "google-cloud-dataproc")
      exclude ("com.google.cloud", "google-cloud-kms")
      exclude ("com.google.cloud", "google-cloud-resourcemanager")
      exclude ("com.google.cloud", "google-cloud-storage-transfer"),
    "org.broadinstitute.dsde.workbench" %% "workbench-oauth2" % s"0.9-$workbenchLibsHash",
    "org.broadinstitute.dsde.workbench" %% "sam-client" % "v0.0.407",
    "org.broadinstitute.dsde.workbench" %% "workbench-notifications" % s"1.1-$workbenchLibsHash",
    "org.databiosphere" % "workspacedataservice-client-okhttp-jakarta" % "0.2.167-SNAPSHOT",
    "bio.terra" % "externalcreds-client-resttemplate" % "1.83.0-SNAPSHOT" excludeAll (excludeSpring, excludeSpringBoot),
    "org.springframework" % "spring-web" % "6.2.9" excludeAll (excludeSpringBoot, excludeSpringJcl),
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV excludeAll (excludeAkkaActor, excludeAkkaStream),
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-testkit" % akkaV % "test",
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % "test",
    "com.github.jwt-scala" %% "jwt-core" % "11.0.2",
    // javax.mail is used only by MethodRepository.validatePublicOrEmail(). Consider
    // refactoring that method to remove this entire dependency.
    "com.sun.mail" % "javax.mail" % "1.6.2"
      exclude ("javax.activation", "activation"),
    "com.univocity" % "univocity-parsers" % "2.9.1",
    "com.github.pathikrit" %% "better-files" % "3.9.2",
    "org.scalatest" %% "scalatest" % "3.2.19" % "test",
    "org.mock-server" % "mockserver-netty-no-dependencies" % "5.15.0" % "test",
    // provides testing mocks
    "com.google.cloud" % "google-cloud-nio" % "0.128.1" % "test",
    "org.scalatestplus" %% "mockito-4-5" % "3.2.12.0" % "test"
  )
}
