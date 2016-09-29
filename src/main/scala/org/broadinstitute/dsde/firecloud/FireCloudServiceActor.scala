package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.dataaccess.{ElasticSearchDAO, HttpRawlsDAO, RawlsDAO, SearchDAO}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.http._
import spray.routing.{HttpServiceActor, Route}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.webservice.LibraryApiService

import scala.concurrent.ExecutionContext.Implicits.global

class FireCloudServiceActor extends HttpServiceActor with FireCloudDirectives with LibraryApiService {
  implicit val system = context.system

  trait ActorRefFactoryContext {
    def actorRefFactory = context
  }

  val rawlsDAO:RawlsDAO = new HttpRawlsDAO
  val searchDAO:SearchDAO = new ElasticSearchDAO

  val app:Application = new Application(rawlsDAO, searchDAO)

  val libraryServiceConstructor: (UserInfo) => LibraryService = LibraryService.constructor(app)

  // insecure cookie-authed routes

  val cookieAuthedService = new CookieAuthedService with ActorRefFactoryContext

  // routes under /api

  val methodsService = new MethodsService with ActorRefFactoryContext
  val workspaceService = new WorkspaceService with ActorRefFactoryContext
  val entityService = new EntityService with ActorRefFactoryContext
  val methodConfigurationService = new MethodConfigurationService with ActorRefFactoryContext
  val submissionsService = new SubmissionService with ActorRefFactoryContext
  val storageService = new StorageService with ActorRefFactoryContext
  val statusService = new StatusService with ActorRefFactoryContext
  val nihService = new NIHService with ActorRefFactoryContext
  val billingService = new BillingService with ActorRefFactoryContext
  val routes = methodsService.routes ~ workspaceService.routes ~ entityService.routes ~
    methodConfigurationService.routes ~ submissionsService.routes ~ storageService.routes ~
    statusService.routes ~ nihService.routes ~ billingService.routes

  val oAuthService = new OAuthService with ActorRefFactoryContext
  val userService = new UserService with ActorRefFactoryContext
  val nihSyncService = new NIHSyncService with ActorRefFactoryContext
  val healthService = new HealthService with ActorRefFactoryContext

  lazy val log = LoggerFactory.getLogger(getClass)
  val logRequests = mapInnerRoute { route => requestContext =>
    log.debug(requestContext.request.toString)
    route(requestContext)
  }
  val appendTimestamp = mapHttpResponse { response =>
    if (response.status.isFailure) {
      try {
        import spray.json._
        import spray.json.DefaultJsonProtocol._
        val dataMap = response.entity.asString.parseJson.convertTo[Map[String, JsValue]]
        val withTimestamp = dataMap + ("timestamp" -> JsNumber(System.currentTimeMillis()))
        response.withEntity(HttpEntity(withTimestamp.toJson.prettyPrint))
      } catch {
        // usually a failure to parse, if the response isn't JSON (e.g. HTML responses from Google)
        case e: Exception => response
      }
    } else response
  }

  // wraps route rejections in an ErrorReport
  import org.broadinstitute.dsde.firecloud.model.ErrorReport.errorReportRejectionHandler

  def receive = runRoute(
    appendTimestamp {
      logRequests {
        swaggerCorsService ~
        swaggerUiService ~
        testNihService ~
        oAuthService.routes ~
        userService.routes ~
        nihSyncService.routes ~
        healthService.routes ~
        libraryRoutes ~
        pathPrefix("api") {
          routes
        } ~
        pathPrefix("cookie-authed") {
          cookieAuthedService.routes
        }
      }
    }
  )

  private val swaggerUiPath = "META-INF/resources/webjars/swagger-ui/2.1.4"

  val swaggerUiService = {
    get {
      optionalHeaderValueByName("X-Forwarded-Host") { forwardedHost =>
        pathPrefix("") {
          pathEnd {
            parameter("url") {urlparam =>
              requestUri {uri =>
                redirect(uri.withQuery(Map.empty[String,String]), MovedPermanently)
              }
            } ~
            serveIndex()
          } ~
            pathSuffix("api-docs") {
              withResourceFileContents("swagger/api-docs.yaml") { apiDocs =>
                complete(apiDocs)
              }
            } ~
            getFromResourceDirectory(swaggerUiPath)
        }
      }
    }
  }

  val swaggerCorsService = {
    options{
      optionalHeaderValueByName("Referer") { refer =>
        refer match {
          // at some point in the future, we may want to support additional referers; careful of hardcoding!
          case Some("https://swagger.dsde-dev.broadinstitute.org/") => complete(OK)
          case _ => reject
        }
      }
    }
  }

  // Placeholder endpoint for testing an authenticated request from NIH. The user will hit this
  // only after successful authentication. Right now, it just echos the request so we can see what
  // we get. TODO(dmohs): Remove or turn into an echo endpoint after testing.
  val testNihService = {
    path("link-nih-account") {
      extract(_.request) { request =>
        complete(
          "Received:\n" + request.method + " " + request.uri + "\n\n"
            + request.headers.mkString("\n") + "\n\n"
            + request.entity + "\n"
        )
      }
    }
  }

  private def serveIndex(): Route = {
    withResourceFileContents(swaggerUiPath + "/index.html") { indexHtml =>
      complete {
        HttpEntity(ContentType(MediaTypes.`text/html`),
          indexHtml
            .replace("your-client-id", FireCloudConfig.Auth.googleClientId)
            .replace("your-realms", FireCloudConfig.Auth.swaggerRealm)
            .replace("your-app-name", FireCloudConfig.Auth.swaggerRealm)
            .replace("scopeSeparator: \",\"", "scopeSeparator: \" \"")
            .replace("url = \"http://petstore.swagger.io/v2/swagger.json\";",
              "url = '/api-docs';")
        )
      }
    }
  }

}

