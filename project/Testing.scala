import sbt.Keys._
import sbt._

object Testing {

  def isIntegrationTest(name: String) = name contains "integrationtest"

  lazy val IntegrationTest = config("it") extend Test

  val commonTestSettings: Seq[Setting[_]] = List(

    testOptions in Test ++= Seq(Tests.Filter(s => !isIntegrationTest(s))),
    testOptions in IntegrationTest := Seq(Tests.Filter(s => isIntegrationTest(s))),

    // ES client attempts to set the number of processors that Netty should use.
    // However, we've already initialized Netty elsewhere (mockserver, I assume),
    // so the call fails. Tell ES to skip attempting to set this value.
    javaOptions in Test += "-Des.set.netty.runtime.available.processors=false",

    fork in Test := true,
    parallelExecution in Test := true
  )

  implicit class ProjectTestSettings(val project: Project) extends AnyVal {
    def withTestSettings: Project = project
      .configs(IntegrationTest).settings(inConfig(IntegrationTest)(Defaults.testTasks): _*)
  }
}

