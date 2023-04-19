package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.elastic.ElasticUtils
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.workbench.oauth2.{ClientId, ClientSecret, OpenIDConnectConfiguration}
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor
import org.elasticsearch.client.transport.TransportClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object Boot extends App with LazyLogging {

  private def startup(): Unit = {
    // we need an ActorSystem to host our application in
    implicit val system: ActorSystem = ActorSystem("FireCloud-Orchestration-API")

    val elasticSearchClient: TransportClient = ElasticUtils.buildClient(FireCloudConfig.ElasticSearch.servers, FireCloudConfig.ElasticSearch.clusterName)

    val agoraDAO:AgoraDAO = new HttpAgoraDAO(FireCloudConfig.Agora)
    val rawlsDAO:RawlsDAO = new HttpRawlsDAO
    val samDAO:SamDAO = new HttpSamDAO
    val thurloeDAO:ThurloeDAO = new HttpThurloeDAO
    val googleServicesDAO:GoogleServicesDAO = new HttpGoogleServicesDAO(FireCloudConfig.GoogleCloud.priceListUrl, GooglePriceList(GooglePrices(FireCloudConfig.GoogleCloud.defaultStoragePriceList, UsTieredPriceItem(FireCloudConfig.GoogleCloud.defaultEgressPriceList)), "v1", "1"))
    val ontologyDAO:OntologyDAO = new ElasticSearchOntologyDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.ontologyIndexName)
    val consentDAO:ConsentDAO = new HttpConsentDAO
    val researchPurposeSupport:ResearchPurposeSupport = new ESResearchPurposeSupport(ontologyDAO)
    val searchDAO:SearchDAO = new ElasticSearchDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.indexName, researchPurposeSupport)
    val shareLogDAO:ShareLogDAO = new ElasticSearchShareLogDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.shareLogIndexName)
    val importServiceDAO:ImportServiceDAO = new HttpImportServiceDAO
    val shibbolethDAO:ShibbolethDAO = new HttpShibbolethDAO

    val app:Application = Application(agoraDAO, googleServicesDAO, ontologyDAO, consentDAO, rawlsDAO, samDAO, searchDAO, researchPurposeSupport, thurloeDAO, shareLogDAO, importServiceDAO, shibbolethDAO);

    val agoraPermissionServiceConstructor: (UserInfo) => AgoraPermissionService = AgoraPermissionService.constructor(app)
    val exportEntitiesByTypeActorConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor = ExportEntitiesByTypeActor.constructor(app, system)
    val entityServiceConstructor: (ModelSchema) => EntityService = EntityService.constructor(app)
    val libraryServiceConstructor: (UserInfo) => LibraryService = LibraryService.constructor(app)
    val ontologyServiceConstructor: () => OntologyService = OntologyService.constructor(app)
    val namespaceServiceConstructor: (UserInfo) => NamespaceService = NamespaceService.constructor(app)
    val nihServiceConstructor: () => NihService = NihService.constructor(app)
    val registerServiceConstructor: () => RegisterService = RegisterService.constructor(app)
    val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app)
    val permissionReportServiceConstructor: (UserInfo) => PermissionReportService = PermissionReportService.constructor(app)
    val userServiceConstructor: (UserInfo) => UserService = UserService.constructor(app)
    val shareLogServiceConstructor: () => ShareLogService = ShareLogService.constructor(app)
    val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService = ManagedGroupService.constructor(app)

    //Boot HealthMonitor actor
    val healthChecks = new HealthChecks(app)
    val healthMonitorChecks = healthChecks.healthMonitorChecks
    val healthMonitor = system.actorOf(HealthMonitor.props(healthMonitorChecks().keySet)( healthMonitorChecks ), "health-monitor")
    system.scheduler.scheduleWithFixedDelay(3.seconds, 1.minute, healthMonitor, HealthMonitor.CheckAll)

    val statusServiceConstructor: () => StatusService = StatusService.constructor(healthMonitor)

    for {
      oauth2Config <- OpenIDConnectConfiguration[IO](
        FireCloudConfig.Auth.authorityEndpoint,
        ClientId(FireCloudConfig.Auth.oidcClientId),
        oidcClientSecret = FireCloudConfig.Auth.oidcClientSecret.map(ClientSecret),
        extraGoogleClientId = FireCloudConfig.Auth.legacyGoogleClientId.map(ClientId),
        extraAuthParams = Some("prompt=login")
      ).unsafeToFuture()(IORuntime.global)

      service = new FireCloudApiServiceImpl(
        agoraPermissionServiceConstructor,
        exportEntitiesByTypeActorConstructor,
        entityServiceConstructor,
        libraryServiceConstructor,
        ontologyServiceConstructor,
        namespaceServiceConstructor,
        nihServiceConstructor,
        registerServiceConstructor,
        workspaceServiceConstructor,
        statusServiceConstructor,
        permissionReportServiceConstructor,
        userServiceConstructor,
        shareLogServiceConstructor,
        managedGroupServiceConstructor,
        oauth2Config
      )

      binding <- Http().newServerAt( "0.0.0.0", 8080).bind(service.route) recover {
        case t: Throwable =>
          logger.error("FATAL - failure starting http server", t)
          throw t
      }
      _ <- binding.whenTerminated
      _ <- system.terminate()

    } yield {

    }
  }

  startup()
}
