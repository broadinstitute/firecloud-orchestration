package org.broadinstitute.dsde.firecloud

import akka.actor.ActorLogging
import com.gettyimages.spray.swagger.SwaggerHttpService
import com.wordnik.swagger.model.ApiInfo
import org.broadinstitute.dsde.firecloud.service.MethodsService
import spray.http.StatusCodes._
import spray.routing.HttpServiceActor

import scala.reflect.runtime.universe._

class FireCloudServiceActor extends HttpServiceActor with ActorLogging {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  override def actorRefFactory = context

  trait ActorRefFactoryContext {
    def actorRefFactory = context
  }

  val methodsService = new MethodsService with ActorRefFactoryContext

  def receive = runRoute(
    swaggerService.routes ~ swaggerUiService ~ methodsService.listRoute
  )

  val swaggerService = new SwaggerHttpService {

    // All documented API services must be added to these API types for Swagger to recognize them.
    override def apiTypes = Seq(
      typeOf[MethodsService]
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

  val swaggerUiService = {
    get {
      pathPrefix("swagger") {
        // if the user just hits "swagger", redirect to the index page with our api docs specified on the url
        pathEndOrSingleSlash { p =>
          // the base context path may be different in various environments
          val dynamicContext = FireCloudConfig.SwaggerConfig.baseUrl
          p.redirect(dynamicContext + "swagger/index.html?url=" + dynamicContext + "api-docs", TemporaryRedirect)
        } ~
          pathPrefix("swagger/index.html") {
            getFromResource("META-INF/resources/webjars/swagger-ui/2.1.0/index.html")
          } ~
          getFromResourceDirectory("META-INF/resources/webjars/swagger-ui/2.1.0")
      }
    }
  }

}
