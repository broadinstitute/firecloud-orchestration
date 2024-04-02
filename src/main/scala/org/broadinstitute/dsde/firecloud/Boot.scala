package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.elastic.ElasticUtils
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.DisabledServiceFactory
import org.broadinstitute.dsde.workbench.oauth2.{ClientId, ClientSecret, OpenIDConnectConfiguration}
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor
import org.elasticsearch.client.transport.TransportClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.reflect.ClassTag

object Boot extends App with LazyLogging {

  private def startup(): Unit = {
    // we need an ActorSystem to host our application in
    implicit val system: ActorSystem = ActorSystem("FireCloud-Orchestration-API")

    val app: Application = buildApplication

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

  private def buildApplication(implicit system: ActorSystem) = {
    // can't be disabled
    val rawlsDAO: RawlsDAO = new HttpRawlsDAO
    val samDAO: SamDAO = new HttpSamDAO
    val thurloeDAO: ThurloeDAO = new HttpThurloeDAO

    // can be disabled
    val agoraDAO: AgoraDAO = whenEnabled(FireCloudConfig.Agora.enabled, new HttpAgoraDAO(FireCloudConfig.Agora))
    val googleServicesDAO: GoogleServicesDAO = whenEnabled(FireCloudConfig.GoogleCloud.enabled, new HttpGoogleServicesDAO(FireCloudConfig.GoogleCloud.priceListUrl, GooglePriceList(GooglePrices(FireCloudConfig.GoogleCloud.defaultStoragePriceList, UsTieredPriceItem(FireCloudConfig.GoogleCloud.defaultEgressPriceList)), "v1", "1")))
    val importServiceDAO: ImportServiceDAO = whenEnabled(FireCloudConfig.ImportService.enabled, new HttpImportServiceDAO)
    val shibbolethDAO: ShibbolethDAO = whenEnabled(FireCloudConfig.Shibboleth.enabled, new HttpShibbolethDAO)
    val cwdsDAO: CwdsDAO = whenEnabled(FireCloudConfig.Cwds.enabled, new HttpCwdsDAO(FireCloudConfig.Cwds.enabled, FireCloudConfig.Cwds.supportedFormats))

    val elasticSearchClient: Option[TransportClient] = Option.when(FireCloudConfig.ElasticSearch.enabled) {
      ElasticUtils.buildClient(FireCloudConfig.ElasticSearch.servers, FireCloudConfig.ElasticSearch.clusterName)
    }

    val ontologyDAO: OntologyDAO = elasticSearchClient.map(new ElasticSearchOntologyDAO(_, FireCloudConfig.ElasticSearch.ontologyIndexName)).getOrElse(DisabledServiceFactory.newDisabledService)
    val researchPurposeSupport: ResearchPurposeSupport = new ESResearchPurposeSupport(ontologyDAO)
    val searchDAO: SearchDAO = elasticSearchClient.map(new ElasticSearchDAO(_, FireCloudConfig.ElasticSearch.indexName, researchPurposeSupport)).getOrElse(DisabledServiceFactory.newDisabledService)
    val shareLogDAO: ShareLogDAO = elasticSearchClient.map(new ElasticSearchShareLogDAO(_, FireCloudConfig.ElasticSearch.shareLogIndexName)).getOrElse(DisabledServiceFactory.newDisabledService)

    Application(agoraDAO, googleServicesDAO, ontologyDAO, rawlsDAO, samDAO, searchDAO, researchPurposeSupport, thurloeDAO, shareLogDAO, importServiceDAO, shibbolethDAO, cwdsDAO)
  }

  private def whenEnabled[T : ClassTag](enabled: Boolean, realService: => T): T = {
    if (enabled) {
      realService
    } else {
      DisabledServiceFactory.newDisabledService
    }
  }

  startup()
}
