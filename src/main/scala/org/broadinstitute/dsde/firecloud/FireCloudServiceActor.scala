package org.broadinstitute.dsde.firecloud

import org.parboiled.common.FileUtils
import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.http.Uri.{Authority, Path}
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
      swaggerUiService ~ routes ~
        // The "api" path prefix is never visible to this server in production because it is
        // transparently proxied. We check for it here so the redirects work when visiting this
        // server directly during local development.
        pathPrefix("api") {
          swaggerUiService ~ routes
        }
    }
  )

  private val uri = extract { c => c.request.uri }
  private val swaggerUiPath = "META-INF/resources/webjars/swagger-ui/2.1.1"

  val swaggerUiService = {
    get {
      optionalHeaderValueByName("X-Forwarded-Host") { forwardedHost =>
        pathPrefix("") {
          pathEnd {
            uri { uri =>
              redirectToSwagger(forwardedHost, uri.withPath(uri.path + "api/swagger/"))
            }
          }
        } ~
          pathPrefix("swagger") {
            pathEnd {
              uri { uri =>
                redirectToSwagger(forwardedHost, uri.withPath(Path("/api") ++ uri.path + "/")) }
            } ~
              pathSingleSlash {
                uri { uri =>
                  redirectToSwagger(forwardedHost, uri.withPath(Path("/api") ++ uri.path))
                }
              } ~
              serveYaml("swagger") ~ getFromResourceDirectory(swaggerUiPath)
          }
      }
    }
  }

  private def redirectToSwagger(forwardedHost: Option[String], baseUri: Uri): Route = {
    val uri = forwardedHost match {
      case Some(x) => baseUri.withAuthority(hostAndPortToAuthority(x))
      case None => baseUri
    }
    redirect(
      uri.withPath(uri.path + "index.html").withQuery(("url", uri.path.toString + "api-docs")),
      TemporaryRedirect
    )
  }

  private def serveYaml(resourceDirectoryBase: String): Route = {
    unmatchedPath { path =>
      val classLoader = actorSystem(actorRefFactory).dynamicAccess.classLoader
      classLoader.getResource(resourceDirectoryBase + path + ".yaml") match {
        case null => reject
        case url => complete {
          val inputStream = url.openStream()
          try {
            FileUtils.readAllText(inputStream)
          } finally {
            inputStream.close()
          }
        }
      }
    }
  }

  private def hostAndPortToAuthority(hostAndPort: String): Authority = {
    val parts = hostAndPort.split(":")
    val host = parts(0)
    if (parts.length > 1)
      Authority(Uri.Host(host), parts(1).toInt)
    else
      Authority(Uri.Host(host))
  }
}

