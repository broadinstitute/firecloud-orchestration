package org.broadinstitute.dsde.firecloud

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{ContentTypes, HttpCharsets, HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.http.scaladsl.server.{Directive0, ExceptionHandler, MalformedRequestContentRejection, MethodRejection, RejectionHandler}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.firecloud.webservice._
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object FireCloudApiService {

  import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
  import spray.json._

  implicit val errorReportSource = ErrorReportSource("FireCloud") //TODO make sure this doesn't clobber source names globally

  val exceptionHandler = {

    import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._

    ExceptionHandler {
      case withErrorReport: FireCloudExceptionWithErrorReport =>
        complete(withErrorReport.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError) -> withErrorReport.errorReport)
      case e: Throwable =>
        complete(StatusCodes.InternalServerError -> ErrorReport(e))
    }
  }

  //TODO: Verify that this doesn't clobber all of the other default rejection handling
  implicit def customRejectionHandler = RejectionHandler.newBuilder().handle {
    case MalformedRequestContentRejection(errorMsg, _) =>
      complete { (StatusCodes.BadRequest, ErrorReport(StatusCodes.BadRequest, errorMsg)) }
  }.handleAll[MethodRejection] { _ =>
    complete { StatusCodes.MethodNotAllowed }
  }.result().mapRejectionResponse {
    case resp@HttpResponse(statusCode, _, ent: HttpEntity.Strict, _) => {
      // since all Akka default rejection responses are Strict this will handle all rejections
      val message = ent.data.utf8String.replaceAll("\"", """\"""")

      resp.withEntity(HttpEntity(ContentTypes.`application/json`, ErrorReport(statusCode, message).toJson.toString))
    }
  }

}

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

  val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor
  val entityServiceConstructor: (ModelSchema) => EntityService
  val libraryServiceConstructor: (UserInfo) => LibraryService
  val ontologyServiceConstructor: () => OntologyService
  val namespaceServiceConstructor: (UserInfo) => NamespaceService
  val nihServiceConstructor: () => NihService
  val registerServiceConstructor: () => RegisterService
  val storageServiceConstructor: (UserInfo) => StorageService
  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService
  val statusServiceConstructor: () => StatusService
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService
  val userServiceConstructor: (UserInfo) => UserService
  val shareLogServiceConstructor: () => ShareLogService
  val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService
  val trialServiceConstructor: () => TrialService
  val agoraPermissionService: (UserInfo) => AgoraPermissionService

  implicit val executionContext: ExecutionContext
  implicit val materializer: Materializer

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

  def route: server.Route = (logRequestResult & handleExceptions(FireCloudApiService.exceptionHandler)/* & appendTimestampOnFailure*/ ) {
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

class FireCloudApiServiceImpl(val agoraPermissionService: (UserInfo) => AgoraPermissionService, val trialServiceConstructor: () => TrialService, val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor, val entityServiceConstructor: (ModelSchema) => EntityService, val libraryServiceConstructor: (UserInfo) => LibraryService, val ontologyServiceConstructor: () => OntologyService, val namespaceServiceConstructor: (UserInfo) => NamespaceService, val nihServiceConstructor: () => NihService, val registerServiceConstructor: () => RegisterService, val storageServiceConstructor: (UserInfo) => StorageService, val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService, val statusServiceConstructor: () => StatusService, val permissionReportServiceConstructor: (UserInfo) => PermissionReportService, val userServiceConstructor: (UserInfo) => UserService, val shareLogServiceConstructor: () => ShareLogService, val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService)(implicit val actorRefFactory: ActorRefFactory, implicit val executionContext: ExecutionContext, val materializer: Materializer, val system: ActorSystem) extends FireCloudApiService with StandardUserInfoDirectives
