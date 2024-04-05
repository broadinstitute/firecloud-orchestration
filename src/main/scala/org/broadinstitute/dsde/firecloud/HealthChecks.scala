package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.ReportsSubsystemStatus
import org.broadinstitute.dsde.firecloud.model.{AccessToken, RegistrationInfo, WorkbenchEnabled}
import org.broadinstitute.dsde.workbench.util.health.Subsystems._
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}

import scala.concurrent.{ExecutionContext, Future}

object HealthChecks {
  val termsOfServiceUrl = "app.terra.bio/#terms-of-service"
}

class HealthChecks(app: Application)
                  (implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
                    extends LazyLogging {

  def healthMonitorChecks: () => Map[Subsystem, Future[SubsystemStatus]] = () => {
    val servicesToMonitor = Seq(app.rawlsDAO, app.samDAO, app.thurloeDAO) ++
      Option.when(FireCloudConfig.Agora.enabled)(app.agoraDAO) ++
      Option.when(FireCloudConfig.GoogleCloud.enabled)(app.googleServicesDAO) ++
      Option.when(FireCloudConfig.ElasticSearch.enabled)(app.searchDAO) ++
      Option.when(FireCloudConfig.ElasticSearch.enabled)(app.ontologyDAO)

    servicesToMonitor.map { subsystem =>
      subsystem.serviceName -> subsystem.status
    }.toMap
  }
}
