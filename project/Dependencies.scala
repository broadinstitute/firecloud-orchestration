import sbt._

object Dependencies {
  val akkaV = "2.5.32"
  val akkaHttpV = "10.2.1"
  val jacksonV = "2.10.0"
  val jacksonHotfixV = "2.10.0" // for when only some of the Jackson libs have hotfix releases

  def excludeGuava(m: ModuleID): ModuleID = m.exclude("com.google.guava", "guava")
  val excludeAkkaActor =        ExclusionRule(organization = "com.typesafe.akka", name = "akka-actor_2.13")
  val excludeAkkaStream =       ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.13")
  val excludeAkkaHttp = ExclusionRule(organization = "com.typesafe.akka", name = "akka-http_2.13");
  val excludeSprayJson = ExclusionRule(organization = "com.typesafe.akka", name = "akka-http-spray-json_2.13");

  val rootDependencies = Seq(
    // proactively pull in latest versions of these libraries, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
    "com.fasterxml.jackson.core"     % "jackson-annotations" % jacksonV,
    "com.fasterxml.jackson.core"     % "jackson-databind"    % jacksonHotfixV,
    "com.fasterxml.jackson.core"     % "jackson-core"        % jacksonV,
    "io.netty"                       % "netty-codec"         % "4.1.46.Final",
    "org.apache.lucene"              % "lucene-queryparser"  % "6.6.2",
    "com.google.guava"               % "guava"               % "28.1-android",
    // END transitive dependency overrides

    "org.apache.logging.log4j"       % "log4j-api"           % "2.8.2", // elasticsearch requires log4j ...
    "org.apache.logging.log4j"       % "log4j-to-slf4j"      % "2.8.2", // ... but we redirect log4j to logback.
    "ch.qos.logback"                 % "logback-classic"     % "1.2.2",
    "com.getsentry.raven"            % "raven-logback"       % "7.8.6",
    "com.typesafe.scala-logging"    %% "scala-logging"       % "3.9.2",

    "org.parboiled" % "parboiled-core" % "1.2.0",
    excludeGuava("org.broadinstitute.dsde"       %% "rawls-model"         % "0.1-2356e282")
      exclude("com.typesafe.scala-logging", "scala-logging_2.13")
      exclude("com.typesafe.akka", "akka-stream_2.13")
      exclude("com.google.code.findbugs", "jsr305")
      excludeAll(excludeAkkaHttp, excludeSprayJson),
    excludeGuava("org.broadinstitute.dsde.workbench" %% "workbench-util"  % "0.6-bc324ba"),

    "com.typesafe.akka"   %%  "akka-actor"           % akkaV,
    "com.typesafe.akka"   %%  "akka-contrib"         % akkaV               excludeAll(excludeAkkaActor, excludeAkkaStream),
    "com.typesafe.akka"   %%  "akka-http-core"       % akkaHttpV           excludeAll(excludeAkkaActor, excludeAkkaStream),
    "com.typesafe.akka"   %%  "akka-slf4j"           % akkaV               excludeAll(excludeAkkaActor),
    "com.typesafe.akka"   %%  "akka-http"            % akkaHttpV           excludeAll(excludeAkkaActor, excludeAkkaStream),
    "com.typesafe.akka"   %%  "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-testkit"         % akkaV     % "test",
    "com.typesafe.akka"   %%  "akka-http-testkit"    % akkaHttpV % "test",

    "net.virtual-void"              %% "json-lenses"               % "0.6.2"  % "test",
    "com.typesafe.akka"             %% "akka-testkit"              % akkaV    % "test",
    "com.typesafe.akka"             %% "akka-slf4j"                % akkaV,
    "com.typesafe.akka"             %% "akka-stream"               % akkaV      excludeAll(excludeAkkaActor),

    "org.elasticsearch.client"       % "transport"           % "5.6.16"
      exclude("io.netty", "netty-codec")
      exclude("io.netty", "netty-transport")
      exclude("io.netty", "netty-resolver")
      exclude("io.netty", "netty-buffer")
      exclude("io.netty", "netty-common"),

    excludeGuava("com.google.apis"     % "google-api-services-storage"      % "v1-rev20190910-1.30.3"),
    excludeGuava("com.google.apis"     % "google-api-services-sheets"       % "v4-rev20191001-1.30.3"),
    excludeGuava("com.google.apis"     % "google-api-services-cloudbilling" % "v1-rev20191005-1.30.3"),
    excludeGuava("com.google.apis"     % "google-api-services-pubsub"       % "v1-rev20191001-1.30.3"),
    excludeGuava("com.google.auth"     % "google-auth-library-oauth2-http"  % "0.18.0"),
    excludeGuava("com.google.apis"     % "google-api-services-admin-directory"  % "directory_v1-rev110-1.25.0"),

    "org.webjars.npm"                % "swagger-ui-dist"     % "3.35.0",
    "org.webjars"                    % "webjars-locator"     % "0.40",
    "com.pauldijou"                 %% "jwt-core"            % "3.1.0",
    "com.sun.mail"                   % "javax.mail"          % "1.5.6",
    "com.univocity"                  % "univocity-parsers"   % "2.4.1",
    "org.ocpsoft.prettytime"         % "prettytime"          % "4.0.1.Final",
    "com.github.everit-org.json-schema" % "org.everit.json.schema" % "1.12.0",
    "com.github.pathikrit"          %% "better-files"        % "3.9.1",
    "org.apache.httpcomponents"      % "httpclient"          % "4.5.3",

    "org.specs2"                    %% "specs2-core"         % "4.10.0"     % "test",
    "org.scalatest"                 %% "scalatest"           % "3.2.3"   % "test",
    "org.mock-server"                % "mockserver-netty"    % "3.10.2"  % "test"
  )
}
