package org.broadinstitute.dsde.firecloud.trial

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestKit
import akka.util.Timeout
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.scalatest.{BeforeAndAfterAll, FreeSpecLike}
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.{Trial, WithAccessToken, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.model.Trial.{CreationStatuses, ProjectRoles, RawlsBillingProjectMembership, TrialProject}
import org.broadinstitute.dsde.firecloud.trial.ProjectManager._
import org.broadinstitute.dsde.rawls.model.RawlsBillingProjectName

import scala.concurrent.Future
import scala.concurrent.duration._

class ProjectManagerSpec extends TestKit(ActorSystem("ProjectManagerSpec")) with FreeSpecLike with BeforeAndAfterAll {


  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  implicit val askTimeout = Timeout(5.seconds) // timeout for getting a response from the ProjectManager actor
  val testTimeout = 3.seconds // timeout for a test to succeed or fail when using awaitCond/awaitAssert
  val testInterval = 150.millis // interval on which to repeatedly re-check a test when using awaitCond/awaitAssert

  val rawlsDAO = new ProjectManagerSpecRawlsDAO
  val trialDAO = new ProjectManagerSpecTrialDAO
  val googleDAO = new MockGoogleServicesDAO

  /*
    call stack for project manager:
      - trialDAO.insertProjectRecord
      - rawlsDAO.createProject
      - rawlsDAO.getProjects
      - trialDAO.setProjectRecordVerified
   */

  "ProjectManager actor, when creating projects" -{
    "should insert a record" in {
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState()
      pm ! StartCreation(1)
      awaitCond(trialDAO.insertCount == 1, testTimeout, testInterval)
      system.stop(pm)
    }
    "should insert multiple records" in {
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState()
      pm ! StartCreation(5)
      awaitCond(trialDAO.insertCount == 5, testTimeout, testInterval)
      system.stop(pm)
    }
    "multiple inserted records should all be unique" in {
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState()
      pm ! StartCreation(5)
      awaitCond(insertedProjects.map(_.name).distinct.size == 5, testTimeout, testInterval)
      system.stop(pm)
    }

    "should impose a delay between multiple project creations" in {
      // with a createDelay of 5 seconds, creating 5 project should take at least 25 seconds
      // previous test proves that we can create 5 projects (in the mocks!) within 3 seconds
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState(createDelay = 5.seconds)
      pm ! StartCreation(5)
      Thread.sleep(3000)
      assert(trialDAO.insertCount < 5)
      system.stop(pm)
    }

    "should trigger project creation in rawls" in {
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState()
      pm ! StartCreation(5)
      awaitCond(trialDAO.insertCount == 5, testTimeout, testInterval)
      awaitCond(rawlsDAO.createCount == 5, testTimeout, testInterval)
      system.stop(pm)
    }

    "should trigger verification of inserted records" in {
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState()
      pm ! StartCreation(5)
      awaitCond(trialDAO.insertCount == 5 && insertedProjects.map(_.name).toSet == verifiedProjects.map(_.name).toSet, testTimeout, testInterval)
      system.stop(pm)
    }
    "should impose a delay between project creation and verification" in {
      // with a verify delay of 5 seconds and a sleep of 3, we should see no verifications.
      // previous test proves that verifications are triggered correctly, this test checks the delay.
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState(verifyDelay = 5.seconds)
      pm ! StartCreation(5)
      Thread.sleep(3000)
      assert(trialDAO.verifiedCount == 0)
      system.stop(pm)
    }
    "should only verify a project once if it verifies on the first try" in {
      // we use the rawls getProjects() call as a proxy for verification - in ProjectManager,
      // getProjects() is only called within the verify handler.
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState()
      pm ! StartCreation(1)
      Thread.sleep(3000)
      // under normal conditions, verification succeeds and only executes once.
      assert(rawlsGetProjectsCallCount == 1)
      system.stop(pm)
    }
    "should re-verify a project if it is still creating" in {
      // we use the rawls getProjects() call as a proxy for verification - in ProjectManager,
      // getProjects() is only called within the verify handler.
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState(mockRawlsDAO = new StillCreatingProjectsRawlsDAO)
      pm ! StartCreation(1)
      Thread.sleep(3000)
      // if projects are still creating, and we have zero verification delay, rawlsGetProjectsCallCount should
      // be much much higher than 1.
      assert(rawlsGetProjectsCallCount > 1 && verifiedProjects.isEmpty)
      system.stop(pm)
    }
    "should mark projects as error during verification if they failed to create" in {
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState(mockRawlsDAO = new BadGetProjectsRawlsDAO)
      pm ! StartCreation(3)
      awaitCond(trialDAO.insertCount == 3, testTimeout, testInterval)
      awaitCond(trialDAO.verifiedCount == 3 && verifiedProjects.forall(p => p.verified && p.status.contains(CreationStatuses.Error)), testTimeout, testInterval)
      system.stop(pm)
    }
    "should mark projects as error during verification if they are not returned by Rawls" in {
      val (pm, rawlsDAO, trialDAO, googleDAO) = initTestState(mockRawlsDAO = new MissingGetProjectsRawlsDAO)
      pm ! StartCreation(3)
      awaitCond(trialDAO.insertCount == 3, testTimeout, testInterval)
      awaitCond(trialDAO.verifiedCount == 3 && verifiedProjects.forall(p => p.verified && p.status.contains(CreationStatuses.Error)), testTimeout, testInterval)
      system.stop(pm)
    }

  }


  private def initTestState(mockRawlsDAO: ProjectManagerSpecRawlsDAO = new ProjectManagerSpecRawlsDAO,
                            mockTrialDAO: ProjectManagerSpecTrialDAO = new ProjectManagerSpecTrialDAO,
                            mockGoogleDAO: ProjectManagerSpecMockGoogleServicesDAO = new ProjectManagerSpecMockGoogleServicesDAO,
                            createDelay: FiniteDuration = Duration.Zero,
                            verifyDelay: FiniteDuration = Duration.Zero):
    (ActorRef, ProjectManagerSpecRawlsDAO, ProjectManagerSpecTrialDAO, ProjectManagerSpecMockGoogleServicesDAO) = {

    initVars

    // start a project manager, with zero delays for creating projects and verifying projects
    val pm = system.actorOf(
      ProjectManager.props(mockRawlsDAO, mockTrialDAO, mockGoogleDAO, createDelay, verifyDelay),
      java.util.UUID.randomUUID().toString)

    (pm, mockRawlsDAO, mockTrialDAO, mockGoogleDAO)
  }


  // ****************************************************
  // mutable shared state variables used by the mock DAOs
  var insertedProjects = Seq.empty[TrialProject]
  var verifiedProjects = Seq.empty[TrialProject]
  var rawlsGetProjectsCallCount = 0
  var rawlsCreatedProjects = Seq.empty[String]
  // ****************************************************

  private def initVars = {
    insertedProjects = Seq.empty[TrialProject]
    verifiedProjects = Seq.empty[TrialProject]
    rawlsGetProjectsCallCount = 0
    rawlsCreatedProjects = Seq.empty[String]
  }

  /**
    * Trial DAO with test instrumentation
    */
  class ProjectManagerSpecTrialDAO extends MockTrialDAO {

    def insertCount = insertedProjects.size
    def verifiedCount = verifiedProjects.size

    override def insertProjectRecord(projectName: RawlsBillingProjectName): TrialProject = {
      if (insertedProjects.exists(p => p.name == projectName)) {
        throw new FireCloudException("ProjectManagerSpecTrialDAO says not unique")
      } else {
        val insertedProject = TrialProject(projectName)
        insertedProjects = insertedProjects :+ insertedProject
        insertedProject
      }
    }

    override def getProjectRecord(projectName: RawlsBillingProjectName): TrialProject = insertedProjects.reverse.head

    override def projectRecordExists(projectName: RawlsBillingProjectName): Boolean = true

    override def setProjectRecordVerified(projectName: RawlsBillingProjectName, verified: Boolean, status: CreationStatuses.CreationStatus): TrialProject = {
      val verifiedProject = TrialProject(projectName, verified, None, Some(status))
      verifiedProjects = verifiedProjects :+ verifiedProject
      verifiedProject
    }

    override def claimProjectRecord(userInfo: WorkbenchUserInfo): TrialProject =
      TrialProject(RawlsBillingProjectName("unittest"))

    override def listUnverifiedProjects: Seq[TrialProject] = Seq.empty[TrialProject]

    override def countProjects: Map[String, Long] = Map.empty[String,Long]

    override def projectReport: Seq[TrialProject] = Seq.empty[TrialProject]
  }

  /**
    * Rawls dao with test instrumentation
    */
  class ProjectManagerSpecRawlsDAO extends MockRawlsDAO {

    def createCount = rawlsCreatedProjects.size

    override def createProject(projectName: String, billingAccount: String)(implicit userToken: WithAccessToken): Future[Boolean] = {
      rawlsCreatedProjects = rawlsCreatedProjects :+ projectName
      Future.successful(true)
    }

    override def getProjects(implicit userToken: WithAccessToken): Future[Seq[Trial.RawlsBillingProjectMembership]] = {
      rawlsGetProjectsCallCount = rawlsGetProjectsCallCount + 1
      val projects = rawlsCreatedProjects map { name =>
        RawlsBillingProjectMembership(RawlsBillingProjectName(name), ProjectRoles.Owner, CreationStatuses.Ready, None)
      }
      Future.successful(projects)
    }
  }

  /**
    * Rawls dao that fails to return projects in the getProjects query
    */
  class MissingGetProjectsRawlsDAO extends ProjectManagerSpecRawlsDAO {
    override def getProjects(implicit userToken: WithAccessToken): Future[Seq[Trial.RawlsBillingProjectMembership]] = {
      rawlsGetProjectsCallCount = rawlsGetProjectsCallCount + 1
      Future.successful(Seq.empty[RawlsBillingProjectMembership])
    }
  }

  /**
    * Rawls dao that says that projects failed to create in Google land
    */
  class BadGetProjectsRawlsDAO extends ProjectManagerSpecRawlsDAO {
    override def getProjects(implicit userToken: WithAccessToken): Future[Seq[Trial.RawlsBillingProjectMembership]] = {
      rawlsGetProjectsCallCount = rawlsGetProjectsCallCount + 1
      val projects = rawlsCreatedProjects map { name =>
        RawlsBillingProjectMembership(RawlsBillingProjectName(name), ProjectRoles.Owner, CreationStatuses.Error, Some("unit test creation error"))
      }
      Future.successful(projects)
    }
  }

  /**
    * Rawls dao that says that projects are still creating in Google land
    */
  class StillCreatingProjectsRawlsDAO extends ProjectManagerSpecRawlsDAO {
    override def getProjects(implicit userToken: WithAccessToken): Future[Seq[Trial.RawlsBillingProjectMembership]] = {
      rawlsGetProjectsCallCount = rawlsGetProjectsCallCount + 1
      val projects = rawlsCreatedProjects map { name =>
        RawlsBillingProjectMembership(RawlsBillingProjectName(name), ProjectRoles.Owner, CreationStatuses.Creating, None)
      }
      Future.successful(projects)
    }
  }

  class ProjectManagerSpecMockGoogleServicesDAO extends MockGoogleServicesDAO

}





