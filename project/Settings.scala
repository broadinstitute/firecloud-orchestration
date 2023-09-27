import Dependencies._
import Merging._
import Testing._
import Version._
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyPlugin.autoImport._

object Settings {

  val artifactory = "https://broadinstitute.jfrog.io/broadinstitute/"

  val commonResolvers = List(
    "artifactory-releases" at artifactory + "libs-release",
    "artifactory-snapshots" at artifactory + "libs-snapshot",
    "jitpack.io" at "https://jitpack.io"
  )

  val proxyResolvers = List(
    "internal-maven-proxy" at artifactory + "maven-central"
  )

  //coreDefaultSettings + defaultConfigs = the now deprecated defaultSettings
  val commonBuildSettings = Defaults.coreDefaultSettings ++ Defaults.defaultConfigs ++ Seq(
    javaOptions += "-Xmx2G",
    javacOptions ++= Seq("--release", "17")
  )

  val commonCompilerSettings = Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-encoding", "utf8",
    "-release:8"
  )

  //sbt assembly settings
  val commonAssemblySettings = Seq(
    assembly / assemblyMergeStrategy := customMergeStrategy((assembly / assemblyMergeStrategy).value),
    assembly / test := {}
  )

  //common settings for all sbt subprojects
  val commonSettings =
    commonBuildSettings ++ commonAssemblySettings ++ commonTestSettings ++ List(
    organization  := "org.broadinstitute.dsde.firecloud",
    scalaVersion  := "2.13.12",
    resolvers := proxyResolvers ++: resolvers.value ++: commonResolvers,
    scalacOptions ++= commonCompilerSettings,
    dependencyOverrides ++= transitiveDependencyOverrides
  )

  //the full list of settings for the root project that's ultimately the one we build into a fat JAR and run
  //coreDefaultSettings (inside commonSettings) sets the project name, which we want to override, so ordering is important.
  //thus commonSettings needs to be added first.
  val rootSettings = commonSettings ++ List(
    name := "FireCloud-Orchestration",
    libraryDependencies ++= rootDependencies
  ) ++ commonAssemblySettings ++ rootVersionSettings


}
