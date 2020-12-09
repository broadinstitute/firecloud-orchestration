package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.elastic.ElasticUtils
import org.broadinstitute.dsde.firecloud.metrics.MetricsActor
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor
import org.elasticsearch.client.transport.TransportClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object Boot extends App with LazyLogging {

  private def startup(): Unit = {
    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("FireCloud-Orchestration-API")
    implicit val materializer = ActorMaterializer()

    val elasticSearchClient: TransportClient = ElasticUtils.buildClient(FireCloudConfig.ElasticSearch.servers, FireCloudConfig.ElasticSearch.clusterName)
    val logitMetricsEnabled = FireCloudConfig.Metrics.logitApiKey.isDefined

    val agoraDAO:AgoraDAO = new HttpAgoraDAO(FireCloudConfig.Agora)
    val rawlsDAO:RawlsDAO = new HttpRawlsDAO
    val samDAO:SamDAO = new HttpSamDAO
    val thurloeDAO:ThurloeDAO = new HttpThurloeDAO
    val googleServicesDAO:GoogleServicesDAO = new HttpGoogleServicesDAO
    val ontologyDAO:OntologyDAO = new ElasticSearchOntologyDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.ontologyIndexName)
    val consentDAO:ConsentDAO = new HttpConsentDAO
    val researchPurposeSupport:ResearchPurposeSupport = new ESResearchPurposeSupport(ontologyDAO)
    val searchDAO:SearchDAO = new ElasticSearchDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.indexName, researchPurposeSupport)
    val logitDAO:LogitDAO = if (logitMetricsEnabled)
      new HttpLogitDAO(FireCloudConfig.Metrics.logitUrl, FireCloudConfig.Metrics.logitApiKey.get)
    else
      new NoopLogitDAO
    val shareLogDAO:ShareLogDAO = new ElasticSearchShareLogDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.shareLogIndexName)
    val importServiceDAO:ImportServiceDAO = new HttpImportServiceDAO

    val app:Application = Application(agoraDAO, googleServicesDAO, ontologyDAO, consentDAO, rawlsDAO, samDAO, searchDAO, researchPurposeSupport, thurloeDAO, logitDAO, shareLogDAO, importServiceDAO);

    val agoraPermissionServiceConstructor: (UserInfo) => AgoraPermissionService = AgoraPermissionService.constructor(app)
    val exportEntitiesByTypeActorConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor = ExportEntitiesByTypeActor.constructor(app, materializer)
    val entityServiceConstructor: (ModelSchema) => EntityService = EntityService.constructor(app)
    val libraryServiceConstructor: (UserInfo) => LibraryService = LibraryService.constructor(app)
    val ontologyServiceConstructor: () => OntologyService = OntologyService.constructor(app)
    val namespaceServiceConstructor: (UserInfo) => NamespaceService = NamespaceService.constructor(app)
    val nihServiceConstructor: () => NihService = NihService.constructor(app)
    val registerServiceConstructor: () => RegisterService = RegisterService.constructor(app)
    val storageServiceConstructor: (UserInfo) => StorageService = StorageService.constructor(app)
    val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app)
    val permissionReportServiceConstructor: (UserInfo) => PermissionReportService = PermissionReportService.constructor(app)
    val userServiceConstructor: (UserInfo) => UserService = UserService.constructor(app)
    val shareLogServiceConstructor: () => ShareLogService = ShareLogService.constructor(app)
    val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService = ManagedGroupService.constructor(app)

    //Boot HealthMonitor actor
    val healthChecks = new HealthChecks(app)
    val healthMonitorChecks = healthChecks.healthMonitorChecks
    val healthMonitor = system.actorOf(HealthMonitor.props(healthMonitorChecks().keySet)( healthMonitorChecks ), "health-monitor")
    system.scheduler.schedule(3.seconds, 1.minute, healthMonitor, HealthMonitor.CheckAll)

    val statusServiceConstructor: () => StatusService = StatusService.constructor(healthMonitor)

    //Boot Logit metrics actor
    if (logitMetricsEnabled) {
      val freq = FireCloudConfig.Metrics.logitFrequencyMinutes
      val metricsActor = system.actorOf(MetricsActor.props(app), "metrics-actor")
      // use a randomized startup delay to avoid multiple instances of this app executing on the same cycle
      val initialDelay = 1 + scala.util.Random.nextInt(10)
      logger.info(s"Logit metrics are enabled: every $freq minutes, starting $initialDelay minutes from now.")
      system.scheduler.schedule(initialDelay.minutes, freq.minutes, metricsActor, MetricsActor.RecordMetrics)
    } else {
      logger.info("Logit apikey not found in configuration. Logit metrics are disabled for this instance.")
    }

    val service = new FireCloudApiServiceImpl(
      agoraPermissionServiceConstructor,
      exportEntitiesByTypeActorConstructor,
      entityServiceConstructor,
      libraryServiceConstructor,
      ontologyServiceConstructor,
      namespaceServiceConstructor,
      nihServiceConstructor,
      registerServiceConstructor,
      storageServiceConstructor,
      workspaceServiceConstructor,
      statusServiceConstructor,
      permissionReportServiceConstructor,
      userServiceConstructor,
      shareLogServiceConstructor,
      managedGroupServiceConstructor
    )

    for {
      _ <- Http().newServerAt( "0.0.0.0", 8080).bind(service.route) recover {
        case t: Throwable =>
          logger.error("FATAL - failure starting http server", t)
          throw t
      }

    } yield {

    }
  }

  startup()
}
