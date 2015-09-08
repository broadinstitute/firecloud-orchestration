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
  val routes = methodsService.routes ~ workspaceService.routes ~ entityService.routes ~
    methodConfigurationService.routes ~ submissionsService.routes

  lazy val log = LoggerFactory.getLogger(getClass)
  val logRequests = mapInnerRoute { route => requestContext =>
    log.debug(requestContext.request.toString)
    route(requestContext)
  }

  def receive = runRoute(
    logRequests {
      // The "service" path prefix is never visible to this server in production because it is
      // transparently proxied. We check for it here so the redirects work when visiting this
      // server directly during local development.
      pathPrefix("service") {
        swaggerUiService ~
          pathPrefix("api") {
            routes
          }
      } ~
        swaggerUiService ~
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

