package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{AccessToken, RegistrationInfo, WorkbenchEnabled}
import org.broadinstitute.dsde.workbench.util.health.Subsystems._
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}

import scala.concurrent.{ExecutionContext, Future}

object HealthChecks {
  val adminSaRegistered = Subsystems.Custom("is_admin_sa_registered")
}

class HealthChecks(app: Application, registerSAs: Boolean = true)
                  (implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
                    extends LazyLogging {

  import HealthChecks._

  /**
    * checks if the admin sa is registered and attempts to register if not
    * @return Some(message) if there is a problem registering
    */
  def maybeRegisterAdminSA:Future[Option[String]] =
    maybeRegisterServiceAccount("Admin SA",
      AccessToken(app.googleServicesDAO.getAdminUserAccessToken))

  private def maybeRegisterServiceAccount(name: String, token: AccessToken): Future[Option[String]] = {
    val lookup = manageRegistration(name, app.samDAO.getRegistrationStatus(token))
    lookup flatMap {
      case Some(err) if registerSAs =>
        logger.warn(s"SA registration lookup found: $err")
        logger.info(s"attempting to register $name ...")
        manageRegistration(name, app.samDAO.registerUser(token))

      case registerMessage =>
        Future.successful(registerMessage)
    }
  }

  private def manageRegistration(name: String, req: Future[RegistrationInfo], retry: Boolean = true): Future[Option[String]] = {
    req map {
      case RegistrationInfo(_, WorkbenchEnabled(true, true, true), _) => None
      case regInfo =>
        Option(s"$name is registered but not fully enabled: $regInfo!")
    } recoverWith {
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.NotFound) =>
        Future.successful(Option(s"$name is not registered!"))
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) =>
        /* Conflict status code indicates that we attempted to register the SA, but it was already registered.
            We can reach here in the case of transient errors during the registration lookup, or in case of an
            infrequent race condition: multiple orch instances see that the SA is not registered, so they all
            attempt to register it in parallel. One registration attempt succeeds but the others find a conflict.
            Add a retry here to get the SA's registration status once more; iif the retry lookup fails should we
            bubble up the Conflict error.
         */
        if (retry) {
          val token = AccessToken(app.googleServicesDAO.getAdminUserAccessToken)
          manageRegistration(name, app.samDAO.getRegistrationStatus(token), retry = false)
        } else {
          Future.successful(Option(s"$name already exists!"))
        }
      case errorReport: FireCloudExceptionWithErrorReport =>
        Future.successful(Option(s"Error on registration status for $name: ${errorReport.getMessage}"))
      case e: Exception =>
        Future.successful(Option(s"Error on registration status for $name: ${e.getMessage}"))
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
      adminSaRegistered -> maybeRegisterAdminSA.map(message => SubsystemStatus(message.isEmpty, message.map(List(_))))
    )
  }

}
