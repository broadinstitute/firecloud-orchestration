package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.elastic.ElasticUtils
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service._
import org.elasticsearch.client.transport.TransportClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object FireCloudApp extends App with LazyLogging {

  // we need an ActorSystem to host our application in
  val system = ActorSystem("FireCloud-Orchestration-API")
  val timeoutDuration = FiniteDuration(FireCloudConfig.HttpConfig.timeoutSeconds, SECONDS)

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

    val app:Application = new Application(agoraDAO, googleServicesDAO, ontologyDAO, consentDAO, rawlsDAO, samDAO, searchDAO, researchPurposeSupport, thurloeDAO, logitDAO, shareLogDAO, importServiceDAO);

    val agoraPermissionServiceConstructor: (UserInfo) => AgoraPermissionService = AgoraPermissionService.constructor(app)
    val trialServiceConstructor: () => TrialService = TrialService.constructor(app)
    val exportEntitiesByTypeActorConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor = ExportEntitiesByTypeActor.constructor(app, materializer)
    val entityServiceConstructor: (ModelSchema) => EntityService = EntityService.constructor(app)
    val libraryServiceConstructor: (UserInfo) => LibraryService = LibraryService.constructor(app)
    val ontologyServiceConstructor: () => OntologyService = OntologyService.constructor(app)
    val namespaceServiceConstructor: (UserInfo) => NamespaceService = NamespaceService.constructor(app)
    val nihServiceConstructor: () => NihService = NihService.constructor(app)
    val registerServiceConstructor: () => RegisterService = RegisterService.constructor(app)
    val storageServiceConstructor: (UserInfo) => StorageService = StorageService.constructor(app)
    val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app)
    val statusServiceConstructor: () => StatusService = StatusService.constructor(null)
    val permissionReportServiceConstructor: (UserInfo) => PermissionReportService = PermissionReportService.constructor(app)
    val userServiceConstructor: (UserInfo) => UserService = UserService.constructor(app)
    val shareLogServiceConstructor: () => ShareLogService = ShareLogService.constructor(app)
    val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService = ManagedGroupService.constructor(app)

    val service = new FireCloudApiServiceImpl(
      samDAO,
      agoraPermissionServiceConstructor,
      trialServiceConstructor,
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
      _ <- Http().bindAndHandle(service.route, "0.0.0.0", 8080) recover {
        case t: Throwable =>
          logger.error("FATAL - failure starting http server", t)
          throw t
      }

    } yield {

    }
  }

  startup()
}
