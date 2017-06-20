package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import better.files.File
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service._
import spray.http._
import spray.routing._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

/**
 * Created by dvoet on 11/16/16.
 */
trait CookieAuthedApiService extends HttpService with PerRequestCreator with FireCloudDirectives
  with FireCloudRequestBuilding with LazyLogging {

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor

  private implicit val executionContext = actorRefFactory.dispatcher

  def cookieAuthedRoutes: Route =
    // download "proxy" for TSV files
    path("workspaces" / Segment / Segment/ "entities" / Segment/ "tsv") {
      (workspaceNamespace, workspaceName, entityType) =>
        formFields('FCtoken, 'attributeNames.?) { (tokenValue, attributeNamesString) =>
          post { requestContext =>
            val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
            val userInfo = UserInfo("dummy", OAuth2BearerToken(tokenValue), -1, "dummy")
            val exportProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, userInfo)
            val exportMessage = ExportEntitiesByTypeActor.ExportEntities(requestContext, workspaceNamespace, workspaceName, entityType, attributeNames)
            val exportActor = actorRefFactory.actorOf(exportProps)
            // Necessary for the actor ask pattern
            implicit val timeout: Timeout = 1.minutes
            lazy val fileFuture = (exportActor ? exportMessage).mapTo[File]
            onComplete(fileFuture) {
              case Success(file) =>
                val httpEntity = file.contentType match {
                  case Some(cType) if cType.contains("text") => HttpEntity(ContentTypes.`text/plain`, file.contentAsString)
                  case _ => HttpEntity(ContentTypes.`application/octet-stream`, file.loadBytes)
                }
                complete(HttpResponse(
                  status = StatusCodes.OK,
                  entity = httpEntity,
                  headers = List(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> file.name)))))
              case _ =>
                complete(StatusCodes.InternalServerError, "Error generating entity download")
            }.apply(requestContext)
          }
        }
    } ~
    path("download" / "b" / Segment / "o" / RestPath) { (bucket, obj) =>
      cookie("FCtoken") { tokenCookie =>
        mapRequest(r => addCredentials(OAuth2BearerToken(tokenCookie.content)).apply(r)) { requestContext =>
          HttpGoogleServicesDAO.getDownload(requestContext, bucket, obj.toString, tokenCookie.content)
        }
      }
    }
}
