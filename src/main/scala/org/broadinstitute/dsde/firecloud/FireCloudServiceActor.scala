package org.broadinstitute.dsde.firecloud

import org.parboiled.common.FileUtils
import org.slf4j.LoggerFactory
import spray.http._
import spray.routing.{HttpServiceActor, Route}
import spray.util._

import org.broadinstitute.dsde.firecloud.service._

class FireCloudServiceActor extends HttpServiceActor {
  implicit val system = context.system

  trait ActorRefFactoryContext {
    def actorRefFactory = context
  }

  val methodsService = new MethodsService with ActorRefFactoryContext
  val workspaceService = new WorkspaceService with ActorRefFactoryContext
  val entityService = new EntityService with ActorRefFactoryContext
  val methodConfigurationService = new MethodConfigurationService with ActorRefFactoryContext
  val submissionsService = new SubmissionService with ActorRefFactoryContext
  val storageService = new StorageService with ActorRefFactoryContext
  val statusService = new StatusService with ActorRefFactoryContext
  val userService = new UserService with ActorRefFactoryContext
  val routes = statusService.routes ~ methodsService.routes ~ workspaceService.routes ~ entityService.routes ~
    methodConfigurationService.routes ~ submissionsService.routes ~ userService.routes ~ storageService.routes

  lazy val log = LoggerFactory.getLogger(getClass)
  val logRequests = mapInnerRoute { route => requestContext =>
    log.debug(requestContext.request.toString)
    route(requestContext)
  }

  // wraps route rejections in an ErrorReport
  import org.broadinstitute.dsde.firecloud.model.ErrorReport.errorReportRejectionHandler

  def receive = runRoute(
    logRequests {
      // The "service" path prefix is never visible to this server in production because it is
      // transparently proxied. We check for it here so the redirects work when visiting this
      // server directly during local development.
      pathPrefix("service") {
        swaggerUiService ~
          testNihService ~
          pathPrefix("api") {
            routes
          }
      } ~
        swaggerUiService ~
        testNihService ~
        pathPrefix("api") {
          routes
        }
    }
  )

  private val swaggerUiPath = "META-INF/resources/webjars/swagger-ui/2.1.1"

  val swaggerUiService = {
    get {
      optionalHeaderValueByName("X-Forwarded-Host") { forwardedHost =>
        pathPrefix("") {
          pathEnd {
            cookie("access_token") { tokenCookie =>
              serveIndex(tokenCookie.content)
            } ~
              complete {
                HttpEntity(ContentType(MediaTypes.`text/html`),
                  getResourceFileContents("swagger/auth-page.html")
                .replace("{{googleClientId}}", FireCloudConfig.Auth.googleClientId)
                )
              }
          } ~
            pathSuffix("api-docs") {
              complete {
                getResourceFileContents("swagger/api-docs.yaml")
              }
            } ~
            getFromResourceDirectory(swaggerUiPath)
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

  private def serveIndex(accessToken: String): Route = {
    val authLine = "$(function() { window.swaggerUi.api.clientAuthorizations.add(" +
      "'key', new SwaggerClient.ApiKeyAuthorization('Authorization', 'Bearer " + accessToken +
      "', 'header')); });"
    val indexHtml = getResourceFileContents(swaggerUiPath + "/index.html")
    complete {
      HttpEntity(ContentType(MediaTypes.`text/html`),
        indexHtml
          .replace("</head>", "<script>" + authLine + "</script>\n</head>")
          .replace("url = \"http://petstore.swagger.io/v2/swagger.json\";",
            "url = '/service/api-docs';")
      )
    }
  }

  private def getResourceFileContents(path: String): String = {
    val classLoader = actorSystem(actorRefFactory).dynamicAccess.classLoader
    val inputStream = classLoader.getResource(path).openStream()
    try {
      FileUtils.readAllText(inputStream)
    } finally {
      inputStream.close()
    }
  }
}

