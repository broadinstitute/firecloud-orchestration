package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{AccessToken, RegistrationInfo, WorkbenchEnabled}
import org.broadinstitute.dsde.workbench.util.health.Subsystems._
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}

import scala.concurrent.{ExecutionContext, Future}

object HealthChecks {
  val termsOfServiceUrl = "app.terra.bio/#terms-of-service"
}

class HealthChecks(app: Application, registerSAs: Boolean = true)
                  (implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
                    extends LazyLogging {

  import HealthChecks._

  def healthMonitorChecks: () => Map[Subsystem, Future[SubsystemStatus]] = () => {
    Map(
      Agora -> app.agoraDAO.status,
      Consent -> app.consentDAO.status,
      GoogleBuckets -> app.googleServicesDAO.status,
      LibraryIndex -> app.searchDAO.status,
      OntologyIndex -> app.ontologyDAO.status,
      Rawls -> app.rawlsDAO.status,
      Sam -> app.samDAO.status,
      Thurloe -> app.thurloeDAO.status
    )
  }

}
