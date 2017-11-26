import sbt._

object Dependencies {
  val akkaV = "2.4.19"
  val sprayV = "1.3.4"
  val jacksonV = "2.8.8"
     // note that jackson-databind overrides this below! 2.8.8.1 is not released for core or annotations.

  val rootDependencies = Seq(
    // proactively pull in latest versions of Jackson libs, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
    "com.fasterxml.jackson.core"     % "jackson-annotations" % jacksonV,
    "com.fasterxml.jackson.core"     % "jackson-databind"    % "2.8.8.1",
    "com.fasterxml.jackson.core"     % "jackson-core"        % jacksonV,

    "org.apache.logging.log4j"       % "log4j-api"           % "2.8.2", // elasticsearch requires log4j ...
    "org.apache.logging.log4j"       % "log4j-to-slf4j"      % "2.8.2", // ... but we redirect log4j to logback.
    "ch.qos.logback"                 % "logback-classic"     % "1.2.2",
    "com.getsentry.raven"            % "raven-logback"       % "7.8.6",
    "com.typesafe.scala-logging"    %% "scala-logging"       % "3.7.2",

    "org.broadinstitute.dsde.vault" %% "vault-common"        % "0.1-19-ca8b927",
    "org.broadinstitute.dsde"       %% "rawls-model"         % "0.1-aab4976-SNAP"
      exclude("com.typesafe.scala-logging", "scala-logging_2.11"),
    "org.broadinstitute.dsde.workbench" %% "workbench-util"  % "0.2-dcca21f",

    "io.spray"                      %% "spray-can"           % sprayV,
    "io.spray"                      %% "spray-routing"       % sprayV,
    "io.spray"                      %% "spray-json"          % "1.3.3",
    "io.spray"                      %% "spray-client"        % sprayV,
    "io.spray"                      %% "spray-testkit"       % sprayV    % "test",
    "com.typesafe.akka"             %% "akka-actor"          % akkaV,
    "com.typesafe.akka"             %% "akka-testkit"        % akkaV     % "test",
    "com.typesafe.akka"             %% "akka-slf4j"          % akkaV,
    "com.typesafe.akka"             %% "akka-stream"         % akkaV,

    "org.elasticsearch.client"       % "transport"           % "5.4.3",

    "com.google.api-client"          % "google-api-client"   % "1.22.0"
      exclude("com.google.guava", "guava-jdk5"),
    "com.google.apis"                % "google-api-services-storage" % "v1-rev58-1.21.0",
    "com.google.apis"                % "google-api-services-compute" % "v1-rev120-1.22.0",

    "org.webjars"                    % "swagger-ui"          % "2.2.5",
    "com.jason-goodwin"             %% "authentikat-jwt"     % "0.4.1",
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
