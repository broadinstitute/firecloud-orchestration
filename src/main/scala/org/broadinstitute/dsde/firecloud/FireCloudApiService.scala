package org.broadinstitute.dsde.firecloud

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.headers.CacheDirectives.{`no-cache`, `no-store`}
import akka.http.scaladsl.model.headers.{RawHeader, `Cache-Control`}
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, StatusCodes}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.http.scaladsl.server.{Directive, Directive0, ExceptionHandler, RouteResult}
import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.firecloud.webservice._
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.broadinstitute.dsde.workbench.oauth2.OpenIDConnectConfiguration
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

object FireCloudApiService extends LazyLogging {

  val exceptionHandler = {

    import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._

    implicit val errorReportSource: ErrorReportSource = ErrorReportSource("FireCloud") //TODO make sure this doesn't clobber source names globally

    ExceptionHandler {
      case withErrorReport: FireCloudExceptionWithErrorReport =>
        extractUri { uri =>
          extractMethod { method =>
            val statusCode = withErrorReport.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError)
            if (statusCode.intValue / 100 == 5) {
              // mimic the log pattern from logRequests, below
              logger.error(s"${method} ${uri}: ${statusCode} error: ${withErrorReport.getMessage}")
            }
            complete(statusCode -> withErrorReport.errorReport)
          }
        }
      case e: Throwable =>
        // so we don't log the error twice when debug is enabled
        if (logger.underlying.isDebugEnabled) {
          logger.debug(e.getMessage, e)
        } else {
          logger.error(e.toString)
        }
        // ErrorReport.apply with "message" kwarg. is specifically used to mute Stack Trace output in HTTP Error Responses
        complete(StatusCodes.InternalServerError -> ErrorReport(message=e.getMessage))
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
  with WorkspaceApiService
  with NotificationsApiService
  with MethodConfigurationApiService
  with BillingApiService
  with SubmissionApiService
  with StatusApiService
  with MethodsApiService
  with Ga4ghApiService
  with UserApiService
  with ShareLogApiService
  with ManagedGroupApiService
  with CromIamApiService
  with HealthApiService
  with StaticNotebooksApiService
  with PerimeterApiService
{

  override lazy val log = LoggerFactory.getLogger(getClass)

  val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor
  val entityServiceConstructor: (ModelSchema) => EntityService
  val libraryServiceConstructor: (UserInfo) => LibraryService
  val ontologyServiceConstructor: () => OntologyService
  val namespaceServiceConstructor: (UserInfo) => NamespaceService
  val nihServiceConstructor: () => NihService
  val registerServiceConstructor: () => RegisterService
  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService
  val statusServiceConstructor: () => StatusService
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService
  val userServiceConstructor: (UserInfo) => UserService
  val shareLogServiceConstructor: () => ShareLogService
  val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService
  val agoraPermissionService: (UserInfo) => AgoraPermissionService
  val oidcConfig: OpenIDConnectConfiguration

  implicit val executionContext: ExecutionContext
  implicit val materializer: Materializer

  private def logRequests: Directive0 = {

    def myLoggingFunction(logger: LoggingAdapter)(req: HttpRequest)(res: RouteResult): Unit = {
      val entry: Option[LogEntry] = res match {
        case Complete(resp) =>
          try {
            val logLevel: LogLevel = resp.status.intValue / 100 match {
              case 5 => Logging.ErrorLevel
              case _ => Logging.DebugLevel // this will log everything, if logback level is set to debug!
            }
            resp.entity match {
              case HttpEntity.Strict(_, data) =>
                val entityAsString = data.decodeString(java.nio.charset.Charset.defaultCharset())
                Option(LogEntry(s"${req.method} ${req.uri}: ${resp.status} entity: $entityAsString", logLevel))
              case _ =>
                // note that some responses, if large enough, are returned as Chunked, and we don't try to log
                // those here.
                None
            }
          } catch {
            case e:Exception =>
              // error when extracting the response, likely in decoding the raw bytes
              None
          }
        case _ => None // route rejections; don't attempt to log

      }
      entry.foreach(_.logTo(logger))
    }

    DebuggingDirectives.logRequestResult(LoggingMagnet(log => myLoggingFunction(log)))
  }

  // So we have the time when users send us error screenshots
  val appendTimestampOnFailure: Directive0 = mapResponse { response =>
    if (response.status.isSuccess()) {
      response
    } else {
      try {
        import spray.json._
        response.mapEntity {
          case HttpEntity.Strict(contentType, data) =>
            data.decodeString(java.nio.charset.Charset.defaultCharset()).parseJson match {
              case jso: JsObject =>
                val withTimestamp = jso.fields + ("timestamp" -> JsNumber(System.currentTimeMillis()))
                HttpEntity.apply(contentType, JsObject(withTimestamp).prettyPrint.getBytes)
              // was not a JsObject
              case _ => HttpEntity.Strict(contentType, data)
            }
          case x => x
        }
      } catch {
        case _: Exception => response
      }
    }
  }

  // Return "cache-control: no-store" and "pragma: no-cache" headers,
  // if those headers don't already exist on the response. This is included
  // in `routeWrappers` below, so it affects all responses from Orch.
  // Note that many Orch APIs are passthroughs, and if the underlying
  // service (Rawls, Sam, etc) already returns these headers, Orch
  // will not overwrite them.
  private val noCacheNoStore: Directive0 = respondWithDefaultHeaders(
    `Cache-Control`(`no-store`),
    RawHeader("Pragma", `no-cache`.value))

  // routes under /api
  def apiRoutes: server.Route =
    options { complete(StatusCodes.OK) } ~
      withExecutionContext(ExecutionContext.global) {
        v1RegisterRoutes ~
        methodsApiServiceRoutes ~
          profileRoutes ~
          cromIamApiServiceRoutes ~
          methodConfigurationRoutes ~
          submissionServiceRoutes ~
          nihRoutes ~
          billingServiceRoutes ~
          shareLogServiceRoutes ~
          staticNotebooksRoutes ~
          perimeterServiceRoutes
      }

  val routeWrappers: Directive[Unit] =
   handleRejections(org.broadinstitute.dsde.firecloud.model.defaultErrorReportRejectionHandler) &
      handleExceptions(FireCloudApiService.exceptionHandler) &
      appendTimestampOnFailure &
      logRequests &
      noCacheNoStore

  def route: server.Route = (routeWrappers) {
    cromIamEngineRoutes ~
      tosRoutes ~
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
      oidcConfig.swaggerRoutes("swagger/api-docs.yaml") ~
      oidcConfig.oauth2Routes ~
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

class FireCloudApiServiceImpl(val agoraPermissionService: (UserInfo) => AgoraPermissionService,
                              val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor,
                              val entityServiceConstructor: (ModelSchema) => EntityService,
                              val libraryServiceConstructor: (UserInfo) => LibraryService,
                              val ontologyServiceConstructor: () => OntologyService,
                              val namespaceServiceConstructor: (UserInfo) => NamespaceService,
                              val nihServiceConstructor: () => NihService,
                              val registerServiceConstructor: () => RegisterService,
                              val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService,
                              val statusServiceConstructor: () => StatusService,
                              val permissionReportServiceConstructor: (UserInfo) => PermissionReportService,
                              val userServiceConstructor: (UserInfo) => UserService,
                              val shareLogServiceConstructor: () => ShareLogService,
                              val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService,
                              val oidcConfig: OpenIDConnectConfiguration)
                             (implicit val actorRefFactory: ActorRefFactory,
                              val executionContext: ExecutionContext,
                              val materializer: Materializer,
                              val system: ActorSystem
                             ) extends FireCloudApiService with StandardUserInfoDirectives
