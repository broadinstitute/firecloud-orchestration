package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{AccessToken, RegistrationInfo}
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}
import org.broadinstitute.dsde.workbench.util.health.Subsystems._
import spray.http.StatusCodes

import scala.concurrent.{ExecutionContext, Future}

object HealthChecks {
  val adminSaRegistered = Subsystems.Custom("is_admin_sa_registered")
  val trialBillingSaRegistered = Subsystems.Custom("is_trial_billing_sa_registered")
}

class HealthChecks(app: Application, registerSAs: Boolean = true)
                  (implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
                    extends LazyLogging {

  import HealthChecks._

  def isAdminSARegistered:Future[Boolean] =
    isServiceAccountRegistered("Admin SA",
      AccessToken(app.googleServicesDAO.getAdminUserAccessToken))

  def isTrialBillingSARegistered:Future[Boolean] =
    isServiceAccountRegistered("Free trial billing SA",
      AccessToken(app.googleServicesDAO.getTrialBillingManagerAccessToken))


  private def isServiceAccountRegistered(name: String, token: AccessToken): Future[Boolean] = {
    val lookup = manageRegistration(name, app.samDAO.getRegistrationStatus(implicitly(token)))
    lookup flatMap { isRegistered =>
      if (!isRegistered && registerSAs) {
        logger.warn(s"attempting to register $name ...")
        manageRegistration(name, app.samDAO.registerUser(implicitly(token)))
      } else {
        Future.successful(isRegistered)
      }
    }
  }

  private def manageRegistration(name: String, req: Future[RegistrationInfo]): Future[Boolean] = {
    req map { regInfo =>
      val fullyRegistered = regInfo.enabled.ldap && regInfo.enabled.allUsersGroup && regInfo.enabled.google
      if (fullyRegistered)
        logger.info(s"$name is properly registered.")
      else
        logger.error(s"***    $name is registered but not fully enabled: ${regInfo.enabled}!!    ***")
      fullyRegistered
    } recover {
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.NotFound) =>
        logger.error(s"***    $name is not registered!!    ***")
        false
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) =>
        logger.error(s"***    $name already exists!!    ***")
        false
      case e: Exception =>
        logger.error(s"***    Error on registration status for $name: ${e.getMessage}    ***")
        false
    }
  }

  def healthMonitorChecks: () => Map[Subsystem, Future[SubsystemStatus]] = () => {
    Map(
      Agora -> app.agoraDAO.status,
      Consent -> app.consentDAO.status,
      GoogleBuckets -> app.googleServicesDAO.status,
      LibraryIndex -> app.searchDAO.status,
      OntologyIndex -> app.ontologyDAO.status,
      Rawls -> app.rawlsDAO.status,
      Sam -> app.samDAO.status,
      Thurloe -> app.thurloeDAO.status,
      adminSaRegistered -> isAdminSARegistered.map(SubsystemStatus(_, None)),
      trialBillingSaRegistered -> isTrialBillingSARegistered.map(SubsystemStatus(_, None))
      // TODO: add free-trial index as a monitorable healthcheck; requires updates to workbench-libs
    )
  }

}
