package org.broadinstitute.dsde.firecloud

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.dataaccess.SamDAO
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.firecloud.webservice._
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

trait FireCloudApiService extends CookieAuthedApiService
  with EntityApiService
  with ExportEntitiesApiService
  with LibraryApiService
  with NamespaceApiService
  with NihApiService
  with OauthApiService
  with RegisterApiService
  with StorageApiService
  with WorkspaceApiService
  with NotificationsApiService
  with MethodConfigurationService
  with BillingService
  with SubmissionService
  with StatusApiService
  with MethodsApiService
  with Ga4ghApiService
  with UserApiService
  with SwaggerApiService
  with ShareLogApiService
  with ManagedGroupApiService
  with CromIamApiService
  with HealthService
  with StaticNotebooksApiService
{

  override lazy val log = LoggerFactory.getLogger(getClass)

  //  implicit val system = context.system
  //
  //  trait ActorRefFactoryContext {
  //    def actorRefFactory = context
  //  }

  //  val elasticSearchClient: TransportClient = ElasticUtils.buildClient(FireCloudConfig.ElasticSearch.servers, FireCloudConfig.ElasticSearch.clusterName)

  //  val logitMetricsEnabled = FireCloudConfig.Metrics.logitApiKey.isDefined
  //  val materializer: ActorMaterializer = ActorMaterializer()

  //  private val healthChecks = new HealthChecks(app)
  //  val healthMonitorChecks = healthChecks.healthMonitorChecks
  //  val healthMonitor = system.actorOf(HealthMonitor.props(healthMonitorChecks().keySet)( healthMonitorChecks ), "health-monitor")
  //  system.scheduler.schedule(3.seconds, 1.minute, healthMonitor, HealthMonitor.CheckAll)
  //
  //
  //  if (logitMetricsEnabled) {
  //    val freq = FireCloudConfig.Metrics.logitFrequencyMinutes
  //    val metricsActor = system.actorOf(MetricsActor.props(app), "metrics-actor")
  //    // use a randomized startup delay to avoid multiple instances of this app executing on the same cycle
  //    val initialDelay = 1 + scala.util.Random.nextInt(10)
  //    logger.info(s"Logit metrics are enabled: every $freq minutes, starting $initialDelay minutes from now.")
  //    system.scheduler.schedule(initialDelay.minutes, freq.minutes, metricsActor, MetricsActor.RecordMetrics)
  //  } else {
  //    logger.info("Logit apikey not found in configuration. Logit metrics are disabled for this instance.")
  //  }

  val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor
  val entityServiceConstructor: (ModelSchema) => EntityService
  val libraryServiceConstructor: (UserInfo) => LibraryService
  val ontologyServiceConstructor: () => OntologyService
  val namespaceServiceConstructor: (UserInfo) => NamespaceService
  val nihServiceConstructor: () => NihServiceActor
  val registerServiceConstructor: () => RegisterService
  val storageServiceConstructor: (UserInfo) => StorageService
  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService
  val statusServiceConstructor: () => StatusService
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService
  val userServiceConstructor: (UserInfo) => UserService
  val shareLogServiceConstructor: () => ShareLogService
  val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService
  val trialServiceConstructor: () => TrialService
  val samDAO: SamDAO
  val agoraPermissionService: (UserInfo) => AgoraPermissionService

  implicit val executionContext: ExecutionContext
  implicit val materializer: Materializer

  val logRequests = mapInnerRoute { route => requestContext =>
    log.debug(requestContext.request.toString)
    route(requestContext)
  }

  // So we have the time when users send us error screenshots
//  val appendTimestampOnFailure = mapResponse { response =>
//    if (response.status.isFailure) {
//      try {
//        import spray.json._
//        val dataMap = response.entity.asString.parseJson.convertTo[Map[String, JsValue]]
//        val withTimestamp = dataMap + ("timestamp" -> JsNumber(System.currentTimeMillis()))
//        val contentType = response.header[HttpHeader.`Content-Type`].map{_.contentType}.getOrElse(ContentTypes.`application/json`)
//        response.withEntity(HttpEntity(contentType, withTimestamp.toJson.prettyPrint + "\n"))
//      } catch {
//        // usually a failure to parse, if the response isn't JSON (e.g. HTML responses from Google)
//        case e: Exception => response
//      }
//    } else response
//  }

  // wraps route rejections in an ErrorReport

  // routes under /api
  def apiRoutes =
    options { complete(StatusCodes.OK) } ~
      withExecutionContext(ExecutionContext.global) {
        methodsApiServiceRoutes ~
          profileRoutes ~
          cromIamApiServiceRoutes ~
          methodConfigurationRoutes ~
          submissionServiceRoutes ~
          nihRoutes ~
          billingServiceRoutes ~
          shareLogServiceRoutes ~
          staticNotebooksRoutes
      }

  def route: server.Route = (logRequests /* & appendTimestampOnFailure*/ ) {
    cromIamEngineRoutes ~
      exportEntitiesRoutes ~
      cromIamEngineRoutes ~
      exportEntitiesRoutes ~
      entityRoutes ~
      healthServiceRoutes ~
      libraryRoutes ~
      namespaceRoutes ~
      oauthRoutes ~
      profileRoutes ~
      registerRoutes ~
      storageRoutes ~
      swaggerRoutes ~
      syncRoute ~
      userServiceRoutes ~
      managedGroupServiceRoutes ~
      workspaceRoutes ~
      notificationsRoutes ~
      statusRoutes ~
      ga4ghRoutes ~
      pathPrefix("api") {
        apiRoutes
      } ~
      // insecure cookie-authed routes
      cookieAuthedRoutes
  }

}

class FireCloudApiServiceImpl(val samDAO: SamDAO, val agoraPermissionService: (UserInfo) => AgoraPermissionService, val trialServiceConstructor: () => TrialService, val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor, val entityServiceConstructor: (ModelSchema) => EntityService, val libraryServiceConstructor: (UserInfo) => LibraryService, val ontologyServiceConstructor: () => OntologyService, val namespaceServiceConstructor: (UserInfo) => NamespaceService, val nihServiceConstructor: () => NihServiceActor, val registerServiceConstructor: () => RegisterService, val storageServiceConstructor: (UserInfo) => StorageService, val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService, val statusServiceConstructor: () => StatusService, val permissionReportServiceConstructor: (UserInfo) => PermissionReportService, val userServiceConstructor: (UserInfo) => UserService, val shareLogServiceConstructor: () => ShareLogService, val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService)(implicit val actorRefFactory: ActorRefFactory, implicit val executionContext: ExecutionContext, val materializer: Materializer, val system: ActorSystem) extends FireCloudApiService with StandardUserInfoDirectives
