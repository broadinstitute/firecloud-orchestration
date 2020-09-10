package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{AccessToken, RegistrationInfo, WorkbenchEnabled, spray2akkaStatus}
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}
import org.broadinstitute.dsde.workbench.util.health.Subsystems._
import spray.http.StatusCodes

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
    val lookup = manageRegistration(name, app.samDAO.getRegistrationStatus(implicitly(token)))
    lookup flatMap {
      case Some(_) if registerSAs =>
        logger.info(s"attempting to register $name ...")
        manageRegistration(name, app.samDAO.registerUser(implicitly(token)))

      case registerMessage =>
        Future.successful(registerMessage)
    }
  }

  private def manageRegistration(name: String, req: Future[RegistrationInfo]): Future[Option[String]] = {
    req map {
      case RegistrationInfo(_, WorkbenchEnabled(true, true, true), _) => None
      case regInfo => Option(s"$name is registered but not fully enabled: ${regInfo.enabled}!")
    } recover {
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(spray2akkaStatus(StatusCodes.NotFound)) =>
        Option(s"$name is not registered!")
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(spray2akkaStatus(StatusCodes.Conflict)) =>
        Option(s"$name already exists!")
      case e: Exception =>
        Option(s"Error on registration status for $name: ${e.getMessage}")
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
