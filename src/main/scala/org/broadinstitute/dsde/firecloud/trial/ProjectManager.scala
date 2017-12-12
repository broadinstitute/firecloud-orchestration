package org.broadinstitute.dsde.firecloud.trial

import akka.actor.{Actor, Props}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO, TrialDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.{CreateProjectsResponse, CreationStatuses}
import org.broadinstitute.dsde.firecloud.model.{AccessToken, WithAccessToken}
import org.broadinstitute.dsde.firecloud.trial.ProjectManager.{Create, StartCreation, Verify}
import org.broadinstitute.dsde.rawls.model.RawlsBillingProjectName
import spray.http.OAuth2BearerToken

import scala.concurrent.duration._
import scala.util.Try

object ProjectManager {

  val DefaultCreateDelay = 2.minutes // pause between creating projects to avoid triggering Google quotas
  val DefaultVerifyDelay = 3.minutes // pause between creating a project and verifying it, to allow Google to finish creation

  val verificationLimit: Int = 60 // with a default of 3 minutes, this gives us 3 hours for Google to create a project

  sealed trait ProjectManagerMessage
  case class StartCreation(count: Int) extends ProjectManagerMessage
  case class Create(current: Int, target: Int) extends ProjectManagerMessage
  case class Verify(projectName: String, attemptCount: Int) extends ProjectManagerMessage

  def props(rawlsDAO: RawlsDAO, trialDAO: TrialDAO, googleDAO: GoogleServicesDAO,
            createDelay: FiniteDuration = DefaultCreateDelay, verifyDelay: FiniteDuration = DefaultVerifyDelay): Props =
    Props(new ProjectManager(rawlsDAO, trialDAO, googleDAO, createDelay, verifyDelay))
}

/**
  * Actor to manage creation of free trial billing projects
  *
  * @param rawlsDAO dao for rawls
  * @param trialDAO dao for the trial pool
  * @param createDelay pause between creating projects to avoid triggering Google quotas
  * @param verifyDelay pause between creating a project and verifying it, to allow Google to finish creation
  */
class ProjectManager(val rawlsDAO: RawlsDAO, val trialDAO: TrialDAO, val googleDAO: GoogleServicesDAO, val createDelay: FiniteDuration, val verifyDelay: FiniteDuration)
  extends Actor with LazyLogging {

  import context.dispatcher

  val billingAcct = FireCloudConfig.Trial.billingAccount

  // these should probably be an enum or the like
  val idle = "idle"
  val creating = "creating"

  var currentStatus = idle

  override def receive: Receive = {
    case StartCreation(count: Int) => sender ! startCreation(count)
    case Create(current: Int, target: Int) => sender ! create(current, target)
    case Verify(projectName: String, attemptCount: Int) => verify(projectName, attemptCount)
  }

  private def startCreation(count: Int): CreateProjectsResponse = {
    getCurrentStatus match {
      case `idle` =>
        if (count < 1) {
          CreateProjectsResponse(success = false, count, Some("You must specify a positive number."))
        } else {
          self ! Create(1, count)
          CreateProjectsResponse(success = true, count, Some(s"$count projects are queued for creation."))
        }
      case x =>
        CreateProjectsResponse(success = false, 0, Some("ProjectManager is already creating projects; don't try to create more!"))
    }
  }

  private def create(current: Int, target: Int) = {
    if (current > target) {
      setCurrentStatus(idle)
      logger.debug("project creation requests complete.")
      // don't stop the actor, because we are likely still verifying
    } else {
      setCurrentStatus(s"$creating: $current / $target")
      logger.debug(s"project creation cycle: $getCurrentStatus")

      // generate a unique project name
      val uniqueProjectName = getAndRecordUniqueProjectName

      uniqueProjectName map { projectName =>
        logger.debug(s"creating name <$projectName> via rawls ...")
        // create project via rawls, after sudoing to the trial billing manager
        val saToken:WithAccessToken = AccessToken(googleDAO.getTrialBillingManagerAccessToken)
        rawlsDAO.createProject(projectName, billingAcct)(saToken) map { createSuccess =>
          if (createSuccess) {
            logger.debug(s"rawls acknowledged create request for <$projectName>.")
            // schedule a verify for the project we just created
            context.system.scheduler.scheduleOnce(verifyDelay, self, Verify(projectName, 1))
          } else {
            logger.warn(s"rawls reports error when creating <$projectName>.")
          }
        }
        // schedule the next creation
        context.system.scheduler.scheduleOnce(createDelay, self, Create(current+1, target))
      }
    }
  }

  private def verify(projectName: String, attemptCount: Int) = {
    if (attemptCount > ProjectManager.verificationLimit) {
      logger.warn(s"Project <$projectName> could not be verified within ${attemptCount-1} tries." +
        s"Abandoning attempts, but will leave unverified for later manual verification.")
    } else {
      logger.debug(s"verifying project <$projectName> ...")
      val rawlsName = RawlsBillingProjectName(projectName)
      val saToken:WithAccessToken = AccessToken(googleDAO.getTrialBillingManagerAccessToken)
      // as the trial billing manager, get the list of all projects I own (no API to get a single project)
      rawlsDAO.getProjects(saToken) map { projects =>
        projects.find(p => p.projectName == rawlsName) match {
          case None => // project doesn't exist in rawls. This shouldn't happen.
            logger.warn(s"Project <$projectName> was created in rawls but not returned by rawls. Calling it an error.")
            trialDAO.setProjectRecordVerified(rawlsName, verified = true, status = CreationStatuses.Error)
          case Some(proj) =>
            proj.creationStatus match {
              case CreationStatuses.Creating =>
                logger.debug(s"Project <$projectName> still creating; will retry verification.")
                context.system.scheduler.scheduleOnce(verifyDelay, self, Verify(projectName, attemptCount + 1))
              case CreationStatuses.Ready =>
                logger.debug(s"Project <$projectName> verified as ready!")
                trialDAO.setProjectRecordVerified(rawlsName, verified = true, status = CreationStatuses.Ready)
              case CreationStatuses.Error =>
                logger.warn(s"Project <$projectName> errored during creation: ${proj.message}")
                logger.debug("underlying error is: " + proj.message)
                trialDAO.setProjectRecordVerified(rawlsName, verified = true, status = CreationStatuses.Error)
            }
        }
      } recover {
        case t:Throwable =>
          logger.warn(s"Error checking status on <$projectName>: ${t.getMessage}")
          context.system.scheduler.scheduleOnce(verifyDelay, self, Verify(projectName, attemptCount + 1))
      }
    }
  }

  private def getAndRecordUniqueProjectName: Option[String] = {
    def verifyUniqueProjectName(name: String, currentAttempt: Int): Option[String] = {
      val sanityLimit = 100
      Try(trialDAO.insertProjectRecord(RawlsBillingProjectName(name))) match {
        case scala.util.Success(project) =>
          logger.debug(s"found unique project name in $currentAttempt attempt(s); recorded in pool.")
          Some(project.name.value)
        case scala.util.Failure(f) =>
          if (currentAttempt > sanityLimit) {
            logger.error(s"Could not generate a unique project name after $currentAttempt tries: ${f.getMessage}")
            None
          } else {
            verifyUniqueProjectName(ProjectNamer.randomName, currentAttempt+1)
          }
      }
    }

    verifyUniqueProjectName(ProjectNamer.randomName, 1)
  }

  private def setCurrentStatus(status: String) = currentStatus = status
  private def getCurrentStatus = currentStatus

}

