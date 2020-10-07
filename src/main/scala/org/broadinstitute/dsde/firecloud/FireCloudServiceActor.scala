package org.broadinstitute.dsde.firecloud

import akka.stream.ActorMaterializer
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.elastic.ElasticUtils
import org.broadinstitute.dsde.firecloud.metrics.MetricsActor
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.webservice._
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor
import org.elasticsearch.client.transport.TransportClient
import org.slf4j.LoggerFactory
import org.webjars.WebJarAssetLocator
import spray.http._
import spray.routing.{HttpServiceActor, RequestContext, Route}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps


class FireCloudServiceActor extends HttpServiceActor with FireCloudDirectives
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
  with StatusApiService
  with MethodsApiService
  with Ga4ghApiService
  with UserApiService
  with ShareLogApiService
  with ManagedGroupApiService
  with CromIamApiService
  with StaticNotebooksApiService
{

  override lazy val log = LoggerFactory.getLogger(getClass)

  implicit val system = context.system

  trait ActorRefFactoryContext {
    def actorRefFactory = context
  }

  val elasticSearchClient: TransportClient = ElasticUtils.buildClient(FireCloudConfig.ElasticSearch.servers, FireCloudConfig.ElasticSearch.clusterName)

  val logitMetricsEnabled = FireCloudConfig.Metrics.logitApiKey.isDefined

  val agoraDAO:AgoraDAO = new HttpAgoraDAO(FireCloudConfig.Agora)
  val rawlsDAO:RawlsDAO = new HttpRawlsDAO
  val samDAO:SamDAO = new HttpSamDAO
  val thurloeDAO:ThurloeDAO = new HttpThurloeDAO
  val googleServicesDAO:GoogleServicesDAO = HttpGoogleServicesDAO
  val ontologyDAO:OntologyDAO = new ElasticSearchOntologyDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.ontologyIndexName)
  val consentDAO:ConsentDAO = new HttpConsentDAO
  val researchPurposeSupport:ResearchPurposeSupport = new ESResearchPurposeSupport(ontologyDAO)
  val searchDAO:SearchDAO = new ElasticSearchDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.indexName, researchPurposeSupport)
  val logitDAO:LogitDAO = if (logitMetricsEnabled)
      new HttpLogitDAO(FireCloudConfig.Metrics.logitUrl, FireCloudConfig.Metrics.logitApiKey.get)
    else
      new NoopLogitDAO
  val shareLogDAO:ShareLogDAO = new ElasticSearchShareLogDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.shareLogIndexName)

  val app:Application = new Application(agoraDAO, googleServicesDAO, ontologyDAO, consentDAO, rawlsDAO, samDAO, searchDAO, researchPurposeSupport, thurloeDAO, logitDAO, shareLogDAO)
  val materializer: ActorMaterializer = ActorMaterializer()

  private val healthChecks = new HealthChecks(app)
  val healthMonitorChecks = healthChecks.healthMonitorChecks
  val healthMonitor = system.actorOf(HealthMonitor.props(healthMonitorChecks().keySet)( healthMonitorChecks ), "health-monitor")
  system.scheduler.schedule(3.seconds, 1.minute, healthMonitor, HealthMonitor.CheckAll)

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

  val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor = ExportEntitiesByTypeActor.constructor(app, materializer)
  val entityClientConstructor: (RequestContext, ModelSchema) => EntityClient = EntityClient.constructor(app)
  val libraryServiceConstructor: (UserInfo) => LibraryService = LibraryService.constructor(app)
  val ontologyServiceConstructor: () => OntologyService = OntologyService.constructor(app)
  val namespaceServiceConstructor: (UserInfo) => NamespaceService = NamespaceService.constructor(app)
  val nihServiceConstructor: () => NihServiceActor = NihService.constructor(app)
  val registerServiceConstructor: () => RegisterService = RegisterService.constructor(app)
  val storageServiceConstructor: (UserInfo) => StorageService = StorageService.constructor(app)
  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app)
  val statusServiceConstructor: () => StatusService = StatusService.constructor(healthMonitor)
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService = PermissionReportService.constructor(app)
  val trialServiceConstructor: () => TrialService = TrialService.constructor(app)
  val userServiceConstructor: (UserInfo) => UserService = UserService.constructor(app)
  val shareLogServiceConstructor: () => ShareLogService = ShareLogService.constructor(app)
  val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService = ManagedGroupService.constructor(app)

  // routes under /api
  val methodConfigurationService = new MethodConfigurationService with ActorRefFactoryContext
  val submissionsService = new SubmissionService with ActorRefFactoryContext
  val billingService = new BillingService with ActorRefFactoryContext
  val apiRoutes = methodsApiServiceRoutes ~ profileRoutes ~ cromIamApiServiceRoutes ~
    methodConfigurationService.routes ~ submissionsService.routes ~
    nihRoutes ~ billingService.routes ~ shareLogServiceRoutes ~
    staticNotebooksRoutes

  val healthService = new HealthService with ActorRefFactoryContext


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

  def receive = runRoute(
    appendTimestampOnFailure {
      logRequests {
        cromIamEngineRoutes ~
        exportEntitiesRoutes ~
        entityRoutes ~
        healthService.routes ~
        libraryRoutes ~
        namespaceRoutes ~
        oauthRoutes ~
        profileRoutes ~
        registerRoutes ~
        storageRoutes ~
        swaggerUiService ~
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
  )

  private val swaggerUiPath = new WebJarAssetLocator().getFullPath("swagger-ui-dist", "index.html").replace("/index.html", "")

  val swaggerUiService = {
    path("") {
      get {
        serveIndex()
      }
    } ~
    path("api-docs.yaml") {
      get {
        withResourceFileContents("swagger/api-docs.yaml") { apiDocs =>
          complete(apiDocs)
        }
      }
    } ~
    // We have to be explicit about the paths here since we're matching at the root URL and we don't
    // want to catch all paths lest we circumvent Spray's not-found and method-not-allowed error
    // messages.
      (pathPrefixTest("swagger-ui") | pathPrefixTest("oauth2") | pathSuffixTest("js")
        | pathSuffixTest("css") | pathPrefixTest("favicon")) {
      get {
        getFromResourceDirectory(swaggerUiPath)
      }
    }
  }

  private def serveIndex(): Route = {
    withResourceFileContents(swaggerUiPath + "/index.html") { indexHtml =>
      complete {
        val swaggerOptions =
          """
            |        validatorUrl: null,
            |        apisSorter: "alpha",
            |        operationsSorter: "alpha"
          """.stripMargin

        HttpEntity(ContentType(MediaTypes.`text/html`),
          indexHtml
            .replace("""url: "https://petstore.swagger.io/v2/swagger.json"""", "url: '/api-docs.yaml'")
            .replace("""layout: "StandaloneLayout"""", s"""layout: "StandaloneLayout", $swaggerOptions""")
            .replace("window.ui = ui", s"""ui.initOAuth({
                                          |        clientId: "${FireCloudConfig.Auth.googleClientId}",
                                          |        clientSecret: "${FireCloudConfig.Auth.swaggerRealm}",
                                          |        realm: "${FireCloudConfig.Auth.swaggerRealm}",
                                          |        appName: "firecloud",
                                          |        scopeSeparator: " ",
                                          |        additionalQueryStringParams: {}
                                          |      })
                                          |      window.ui = ui
                                          |      """.stripMargin)
        )
      }
    }
  }

}
