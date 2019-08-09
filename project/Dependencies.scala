import sbt._

object Dependencies {
  val akkaV = "2.4.19"
  val sprayV = "1.3.4"
  val jacksonV = "2.9.9"
  val jacksonHotfixV = "2.9.9.3" // for when only some of the Jackson libs have hotfix releases

  def excludeGuavaJDK5(m: ModuleID): ModuleID = m.exclude("com.google.guava", "guava-jdk5")

  val rootDependencies = Seq(
    // proactively pull in latest versions of Jackson libs, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
    "com.fasterxml.jackson.core"     % "jackson-annotations" % jacksonV,
    "com.fasterxml.jackson.core"     % "jackson-databind"    % jacksonHotfixV,
    "com.fasterxml.jackson.core"     % "jackson-core"        % jacksonV,

    "org.apache.logging.log4j"       % "log4j-api"           % "2.8.2", // elasticsearch requires log4j ...
    "org.apache.logging.log4j"       % "log4j-to-slf4j"      % "2.8.2", // ... but we redirect log4j to logback.
    "ch.qos.logback"                 % "logback-classic"     % "1.2.2",
    "com.getsentry.raven"            % "raven-logback"       % "7.8.6",
    "com.typesafe.scala-logging"    %% "scala-logging"       % "3.7.2",

    "org.broadinstitute.dsde.vault" %% "vault-common"        % "0.1-19-ca8b927"
      exclude("io.spray", "spray-routing_2.11"),
    "org.broadinstitute.dsde"       %% "rawls-model"         % "0.1-b9d04a47"
      exclude("com.typesafe.scala-logging", "scala-logging_2.11") exclude("com.typesafe.akka", "akka-stream_2.11"),
    "org.broadinstitute.dsde.workbench" %% "workbench-util"  % "0.3-12b7791-SNAP",

  "io.spray"                      %% "spray-can"                 % sprayV,
    "io.spray"                      %% "spray-json"                % "1.3.3",
    "io.spray"                      %% "spray-client"              % sprayV,
    "io.spray"                      %% "spray-routing-shapeless23" % sprayV,
    "io.spray"                      %% "spray-testkit"             % sprayV   % "test",
    "net.virtual-void"              %% "json-lenses"               % "0.6.2"  % "test",
    "com.typesafe.akka"             %% "akka-actor"                % akkaV,
    "com.typesafe.akka"             %% "akka-testkit"              % akkaV    % "test",
    "com.typesafe.akka"             %% "akka-slf4j"                % akkaV,
    "com.typesafe.akka"             %% "akka-stream"               % akkaV,

    "org.elasticsearch.client"       % "transport"           % "5.4.3",

    "com.google.guava"               % "guava"               % "19.0",

    excludeGuavaJDK5("com.google.apis"                % "google-api-services-storage" % "v1-rev116-1.23.0"),
    excludeGuavaJDK5("com.google.apis"                % "google-api-services-compute" % "v1-rev163-1.23.0"),
    excludeGuavaJDK5("com.google.apis"                % "google-api-services-sheets"  % "v4-rev494-1.23.0"),
    excludeGuavaJDK5("com.google.apis"                % "google-api-services-cloudbilling" % "v1-rev14-1.23.0"),
    excludeGuavaJDK5("com.google.apis"                % "google-api-services-pubsub"       % "v1-rev357-1.22.0"),

    "org.webjars"                    % "swagger-ui"          % "2.2.5",
    "com.jason-goodwin"             %% "authentikat-jwt"     % "0.4.5"
      exclude("com.fasterxml.jackson.core", "jackson-databind"),
    "com.sun.mail"                   % "javax.mail"          % "1.5.6",
    "com.univocity"                  % "univocity-parsers"   % "2.4.1",
    "org.ocpsoft.prettytime"         % "prettytime"          % "4.0.1.Final",
    "org.everit.json"                % "org.everit.json.schema" % "1.4.1",
    "com.github.pathikrit"          %% "better-files"        % "2.17.1",
    "org.apache.httpcomponents"      % "httpclient"          % "4.5.3",

    "org.specs2"                    %% "specs2-core"         % "3.7"     % "test",
    "org.scalatest"                 %% "scalatest"           % "2.2.6"   % "test",
    "org.mock-server"                % "mockserver-netty"    % "3.10.2"  % "test"
  )
}
