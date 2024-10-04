import sbt.Keys._
import sbt._

object Testing {

  def isIntegrationTest(name: String) = name contains "integrationtest"

  lazy val IntegrationTest = config("it") extend Test

  val commonTestSettings: Seq[Setting[_]] = List(

    Test / testOptions ++= Seq(Tests.Filter(s => !isIntegrationTest(s))),
    Test / testOptions += Tests.Argument("-oD"), // D = individual test durations
    IntegrationTest / testOptions := Seq(Tests.Filter(s => isIntegrationTest(s))),

    // ES client attempts to set the number of processors that Netty should use.
    // However, we've already initialized Netty elsewhere (mockserver, I assume),
    // so the call fails. Tell ES to skip attempting to set this value.
    Test / javaOptions += "-Des.set.netty.runtime.available.processors=false",

    Test / fork := true,
    Test / parallelExecution := false,
    IntegrationTest / fork := false, // allow easy overriding of conf values via system props

  )

  implicit class ProjectTestSettings(val project: Project) extends AnyVal {
    def withTestSettings: Project = project
      .configs(IntegrationTest).settings(inConfig(IntegrationTest)(Defaults.testTasks): _*)
  }
}

