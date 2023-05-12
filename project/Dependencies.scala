import sbt._

object Dependencies {
  val akkaV = "2.6.19"
  val akkaHttpV = "10.2.10"
  val jacksonV = "2.13.4"
  val jacksonHotfixV = "2.13.4.2" // for when only some of the Jackson libs have hotfix releases
  val nettyV = "4.1.92.Final"
  val workbenchLibsHash = "084d25b"

  def excludeGuava(m: ModuleID): ModuleID = m.exclude("com.google.guava", "guava")
  val excludeAkkaActor =        ExclusionRule(organization = "com.typesafe.akka", name = "akka-actor_2.13")
  val excludeAkkaStream =       ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.13")
  val excludeAkkaHttp = ExclusionRule(organization = "com.typesafe.akka", name = "akka-http_2.13")
  val excludeSprayJson = ExclusionRule(organization = "com.typesafe.akka", name = "akka-http-spray-json_2.13")

  // Overrides for transitive dependencies. These apply - via Settings.scala - to all projects in this codebase.
  // These are overrides only; if the direct dependencies stop including any of these, they will not be included
  // by being listed here.
  // One reason to specify an override here is to avoid static-analysis security warnings.
  val transitiveDependencyOverrides: Seq[ModuleID] = Seq(
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonV,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonHotfixV,
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonV,
    "org.yaml" % "snakeyaml" % "1.33",
    // workbench-google2 has jose4j as a dependency; directly updating to a non-vulnerable version until workbench-google2 updates
    "org.bitbucket.b_c" % "jose4j" % "0.9.3"
  )

  val rootDependencies: Seq[ModuleID] = Seq(
    // proactively pull in latest versions of these libraries, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
    // TODO: can these move to sbt's dependencyOverrides?
    "io.netty"                       % "netty-handler"       % nettyV, // netty is needed by the Elasticsearch client at runtime
    "org.apache.lucene"              % "lucene-queryparser"  % "6.6.6", // pin to this version; it's the latest compatible with our elasticsearch client
    "com.google.guava"               % "guava"               % "31.1-jre",
    // END transitive dependency overrides

    // elasticsearch requires log4j, but we redirect log4j to logback
    "org.apache.logging.log4j"       % "log4j-to-slf4j"      % "2.20.0",
    "ch.qos.logback"                 % "logback-classic"     % "1.4.7",
    "com.getsentry.raven"            % "raven-logback"       % "8.0.3", // TODO: this should be io.sentry / sentry-logback instead
    "com.typesafe.scala-logging"    %% "scala-logging"       % "3.9.5",

    "org.parboiled" % "parboiled-core" % "1.4.1",
    excludeGuava("org.broadinstitute.dsde"       %% "rawls-model"         % "0.1-04a7a76b")
      exclude("com.typesafe.scala-logging", "scala-logging_2.13")
      exclude("com.typesafe.akka", "akka-stream_2.13")
      exclude("com.google.code.findbugs", "jsr305")
      exclude("bio.terra", "workspace-manager-client")
      excludeAll(excludeAkkaHttp, excludeSprayJson),
    excludeGuava("org.broadinstitute.dsde.workbench" %% "workbench-util"  % "0.7-01a11c3"),
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % s"0.25-$workbenchLibsHash",
    "org.broadinstitute.dsde.workbench" %% "workbench-oauth2" % s"0.2-$workbenchLibsHash",
    "org.broadinstitute.dsde.workbench" %% "sam-client"       % "0.1-ef83073",
    "org.broadinstitute.dsde.workbench" %% "workbench-notifications" %s"0.3-$workbenchLibsHash",

    "com.typesafe.akka"   %%  "akka-actor"           % akkaV,
    "com.typesafe.akka"   %%  "akka-slf4j"           % akkaV,
    "com.typesafe.akka"   %%  "akka-http"            % akkaHttpV           excludeAll(excludeAkkaActor, excludeAkkaStream),
    "com.typesafe.akka"   %%  "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-stream"          % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"         % akkaV     % "test",
    "com.typesafe.akka"   %%  "akka-http-testkit"    % akkaHttpV % "test",

    "net.virtual-void"              %% "json-lenses"               % "0.6.2"  % "test",

    "org.elasticsearch.client"       % "transport"           % "5.6.16" // pin to this version; it's the latest compatible with our elasticsearch server
      exclude("io.netty", "netty-codec")
      exclude("io.netty", "netty-transport")
      exclude("io.netty", "netty-resolver")
      exclude("io.netty", "netty-buffer")
      exclude("io.netty", "netty-common")
      exclude("io.netty", "netty-codec-http")
      exclude("io.netty", "netty-handler")
      exclude("com.fasterxml.jackson.dataformat", "jackson-dataformat-cbor")
      exclude("org.apache.logging.log4j", "log4j-api")
      exclude("org.apache.logging.log4j", "log4j-core"),


    excludeGuava("com.google.apis"     % "google-api-services-pubsub"       % "v1-rev20230425-2.0.0"),
    excludeGuava("com.google.apis"     % "google-api-services-admin-directory"  % "directory_v1-rev20230425-2.0.0"),


    "com.github.jwt-scala"          %% "jwt-core"            % "9.2.0",
    // javax.mail is used only by MethodRepository.validatePublicOrEmail(). Consider
    // refactoring that method to remove this entire dependency.
    "com.sun.mail"                   % "javax.mail"          % "1.6.2"
      exclude("javax.activation", "activation"),
    "com.univocity"                  % "univocity-parsers"   % "2.9.1",
    "com.github.erosb"               % "everit-json-schema"  % "1.14.2",
    "com.github.pathikrit"          %% "better-files"        % "3.9.2",

    "org.scalatest"                 %% "scalatest"           % "3.2.15"   % "test",
    "org.mock-server"                % "mockserver-netty"    % "3.11"  % "test", // TODO: upgrading higher causes failures, need to investigate
    // jaxb-api needed by WorkspaceApiServiceSpec.bagitService() method
    "javax.xml.bind"                 % "jaxb-api"            % "2.3.1"   % "test",
    // provides testing mocks
    "com.google.cloud"               % "google-cloud-nio"    % "0.126.14" % "test",
    "org.scalatestplus"             %% "mockito-4-5"         % "3.2.12.0" % "test"
  )
}
