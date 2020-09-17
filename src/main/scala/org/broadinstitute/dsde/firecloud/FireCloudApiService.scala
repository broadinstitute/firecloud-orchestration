package org.broadinstitute.dsde.firecloud

import akka.event.{Logging, LoggingAdapter}
import akka.event.Logging.LogLevel
import akka.http.scaladsl.model.{ContentType, HttpCharsets, HttpEntity, HttpRequest, MediaTypes, StatusCodes}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.{Directive0, Route}
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Sink
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.elastic.ElasticUtils
import org.broadinstitute.dsde.firecloud.metrics.MetricsActor
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo, WithAccessToken}

import scala.concurrent.{ExecutionContext, Future}
import org.slf4j.LoggerFactory
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.webservice._
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor
import org.elasticsearch.client.transport.TransportClient

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps


trait FireCloudApiService extends FireCloudDirectives
  with CookieAuthedApiService
  with EntityService
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

  //  val agoraDAO:AgoraDAO = new HttpAgoraDAO(FireCloudConfig.Agora)
  //  val rawlsDAO:RawlsDAO = new HttpRawlsDAO
  //  val samDAO:SamDAO = new HttpSamDAO
  //  val thurloeDAO:ThurloeDAO = new HttpThurloeDAO
  //  val googleServicesDAO:GoogleServicesDAO = HttpGoogleServicesDAO
  //  val ontologyDAO:OntologyDAO = new ElasticSearchOntologyDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.ontologyIndexName)
  //  val consentDAO:ConsentDAO = new HttpConsentDAO
  //  val researchPurposeSupport:ResearchPurposeSupport = new ESResearchPurposeSupport(ontologyDAO)
  //  val searchDAO:SearchDAO = new ElasticSearchDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.indexName, researchPurposeSupport)
  //  val trialDAO:TrialDAO = new ElasticSearchTrialDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.trialIndexName)
  //  val logitDAO:LogitDAO = if (logitMetricsEnabled)
  //      new HttpLogitDAO(FireCloudConfig.Metrics.logitUrl, FireCloudConfig.Metrics.logitApiKey.get)
  //    else
  //      new NoopLogitDAO
  //  val shareLogDAO:ShareLogDAO = new ElasticSearchShareLogDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.shareLogIndexName)

  //  val app:Application = new Application(agoraDAO, googleServicesDAO, ontologyDAO, consentDAO, rawlsDAO, samDAO, searchDAO, researchPurposeSupport, thurloeDAO, trialDAO, logitDAO, shareLogDAO)
  //  val materializer: ActorMaterializer = ActorMaterializer()

  //  private val healthChecks = new HealthChecks(app)
  //  val healthMonitorChecks = healthChecks.healthMonitorChecks
  //  val healthMonitor = system.actorOf(HealthMonitor.props(healthMonitorChecks().keySet)( healthMonitorChecks ), "health-monitor")
  //  system.scheduler.schedule(3.seconds, 1.minute, healthMonitor, HealthMonitor.CheckAll)
  //
  //  val trialProjectManager = system.actorOf(ProjectManager.props(app.rawlsDAO, app.trialDAO, app.googleServicesDAO), "trial-project-manager")
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

  val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor //= ExportEntitiesByTypeActor.constructor(app, materializer)
  val entityClientConstructor: (ModelSchema) => EntityClient //= EntityClient.constructor(app)
  val libraryServiceConstructor: (UserInfo) => LibraryService //= LibraryService.constructor(app)
  val ontologyServiceConstructor: () => OntologyService //= OntologyService.constructor(app)
  val namespaceServiceConstructor: (UserInfo) => NamespaceService //= NamespaceService.constructor(app)
  val nihServiceConstructor: () => NihServiceActor //= NihService.constructor(app)
  val registerServiceConstructor: () => RegisterService //= RegisterService.constructor(app)
  val storageServiceConstructor: (UserInfo) => StorageService //= StorageService.constructor(app)
  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService //= WorkspaceService.constructor(app)
  val statusServiceConstructor: () => StatusService //= StatusService.constructor(healthMonitor)
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService //= PermissionReportService.constructor(app)
  val userServiceConstructor: (UserInfo) => UserService //= UserService.constructor(app)
  val shareLogServiceConstructor: () => ShareLogService //= ShareLogService.constructor(app)
  val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService //= ManagedGroupService.constructor(app)

  implicit val materializer: Materializer

  // routes under /api
  //  val methodConfigurationService = new MethodConfigurationService with ActorRefFactoryContext
  //  val submissionsService = new SubmissionService with ActorRefFactoryContext
  //  val billingService = new BillingService with ActorRefFactoryContext
  //  val apiRoutes = methodsApiServiceRoutes ~ profileRoutes ~ cromIamApiServiceRoutes ~
  //    methodConfigurationService.routes ~ submissionsService.routes ~
  //    nihRoutes ~ billingService.routes ~ trialApiServiceRoutes ~ shareLogServiceRoutes ~
  //    staticNotebooksRoutes

  //  val healthService = new HealthService with ActorRefFactoryContext


  val logRequests = mapInnerRoute { route => requestContext =>
    log.debug(requestContext.request.toString)
    route(requestContext)
  }

  // So we have the time when users send us error screenshots
  val appendTimestampOnFailure = mapHttpResponse { response =>
    if (response.status.isFailure) {
      try {
        import spray.json.DefaultJsonProtocol._
        import spray.json._
        val dataMap = response.entity.asString.parseJson.convertTo[Map[String, JsValue]]
        val withTimestamp = dataMap + ("timestamp" -> JsNumber(System.currentTimeMillis()))
        val contentType = response.header[HttpHeaders.`Content-Type`].map{_.contentType}.getOrElse(ContentTypes.`application/json`)
        response.withEntity(HttpEntity(contentType, withTimestamp.toJson.prettyPrint + "\n"))
      } catch {
        // usually a failure to parse, if the response isn't JSON (e.g. HTML responses from Google)
        case e: Exception => response
      }
    } else response
  }

  // wraps route rejections in an ErrorReport
  import org.broadinstitute.dsde.firecloud.model.errorReportRejectionHandler


  def apiRoutes =
    options { complete(StatusCodes.OK) } ~
      withExecutionContext(ExecutionContext.global) {
        methodsApiServiceRoutes ~
          profileRoutes ~
          cromIamApiServiceRoutes ~
          methodConfigurationRoutes ~
          submissionsService.routes ~
          nihRoutes ~
          billingService.routes ~
          shareLogServiceRoutes ~
          staticNotebooksRoutes
      }

  def route: server.Route = (logRequestResult /* & appendTimestampOnFailure*/ ) {
    cromIamEngineRoutes ~
      exportEntitiesRoutes ~
      cromIamEngineRoutes ~
      exportEntitiesRoutes ~
      entityRoutes ~
      healthRoutes ~
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

  // basis for logRequestResult lifted from http://stackoverflow.com/questions/32475471/how-does-one-log-akka-http-client-requests
  private def logRequestResult: Directive0 = {
    def entityAsString(entity: HttpEntity): Future[String] = {
      entity.dataBytes
        .map(_.decodeString(entity.contentType.charsetOption.getOrElse(HttpCharsets.`UTF-8`).value))
        .runWith(Sink.head)
    }

    def myLoggingFunction(logger: LoggingAdapter)(req: HttpRequest)(res: Any): Unit = {
      val entry = res match {
        case Complete(resp) =>
          val logLevel: LogLevel = resp.status.intValue / 100 match {
            case 5 => Logging.ErrorLevel
            case 4 => Logging.InfoLevel
            case _ => Logging.DebugLevel
          }
          entityAsString(resp.entity).map(data => LogEntry(s"${req.method} ${req.uri}: ${resp.status} entity: $data", logLevel))
        case other =>
          Future.successful(LogEntry(s"$other", Logging.DebugLevel)) // I don't really know when this case happens
      }
      entry.map(_.logTo(logger))
    }

    DebuggingDirectives.logRequestResult(LoggingMagnet(log => myLoggingFunction(log)))
  }

}
