package org.broadinstitute.dsde.firecloud

import scala.reflect.runtime.universe._

import akka.actor.ActorLogging
import com.gettyimages.spray.swagger.SwaggerHttpService
import com.wordnik.swagger.model.ApiInfo
import spray.http.StatusCodes._
import spray.http.Uri
import spray.http.Uri.Path
import spray.routing.{HttpServiceActor, Route}

import org.broadinstitute.dsde.firecloud.service.{EntityService, MethodsService, WorkspaceService}

class FireCloudServiceActor extends HttpServiceActor with ActorLogging {

  trait ActorRefFactoryContext {
    def actorRefFactory = context
  }

  val methodsService = new MethodsService with ActorRefFactoryContext
  val workspaceService = new WorkspaceService with ActorRefFactoryContext
  val entityService = new EntityService with ActorRefFactoryContext

  def receive = runRoute(swaggerUiService ~ methodsService.routes ~ workspaceService.routes ~ entityService.routes)

  val swaggerService = new SwaggerHttpService {

    // All documented API services must be added to these API types for Swagger to recognize them.
    override def apiTypes = Seq(
      typeOf[MethodsService],
      typeOf[WorkspaceService],
      typeOf[EntityService]
    )
    override def apiVersion = FireCloudConfig.SwaggerConfig.apiVersion
    override def baseUrl = FireCloudConfig.SwaggerConfig.baseUrl
    override def docsPath = FireCloudConfig.SwaggerConfig.apiDocs
    override def actorRefFactory = context
    override def apiInfo = Some(
      new ApiInfo(
        FireCloudConfig.SwaggerConfig.info,
        FireCloudConfig.SwaggerConfig.description,
        FireCloudConfig.SwaggerConfig.termsOfServiceUrl,
        FireCloudConfig.SwaggerConfig.contact,
        FireCloudConfig.SwaggerConfig.license,
        FireCloudConfig.SwaggerConfig.licenseUrl)
    )

  }

  private val uri = extract { c => c.request.uri }
  private val swaggerUiPath = "META-INF/resources/webjars/swagger-ui/2.1.0"

  val swaggerUiService = {
    get {
      pathPrefix("") { pathEnd{ uri { uri =>
        redirectToSwagger(uri.withPath(uri.path + "api/swagger/"))
      } } } ~
      swaggerService.routes ~
      // The "api" path prefix is never visible to this server in production because it is
      // transparently proxied. We check for it here so the redirects work when visiting this server
      // directly during local development.
      pathPrefix("api") {
        pathEnd { uri { uri => redirectToSwagger(uri.withPath(uri.path + "/swagger/")) } } ~
        pathSingleSlash { uri { uri => redirectToSwagger(uri.withPath(uri.path + "swagger/")) } } ~
        pathPrefix("swagger") {
          pathEnd { uri { uri => redirectToSwagger(uri.withPath(uri.path + "/")) } } ~
          pathSingleSlash { uri { uri => redirectToSwagger(uri) } } ~
          getFromResourceDirectory(swaggerUiPath)
        } ~
        swaggerService.routes
      } ~
      pathPrefix("swagger") {
        pathEnd { uri { uri => redirectToSwagger(uri.withPath(Path("/api") ++ uri.path + "/")) } } ~
        pathSingleSlash { uri { uri =>
          redirectToSwagger(uri.withPath(Path("/api") ++ uri.path))
        } } ~
        getFromResourceDirectory(swaggerUiPath)
      }
    }
  }

  private def redirectToSwagger(baseUri: Uri): Route = {
    var head = Path("")
    var tail = baseUri.path
    while (tail.length > 2) {
      head = head + tail.head.toString
      tail = tail.tail
    }
    val pathWithoutLastSegment = head
    redirect(
      baseUri.withPath(baseUri.path + "index.html").withQuery(
        ("url", (pathWithoutLastSegment + FireCloudConfig.SwaggerConfig.apiDocs).toString())
      ),
      TemporaryRedirect
    )
  }
}
