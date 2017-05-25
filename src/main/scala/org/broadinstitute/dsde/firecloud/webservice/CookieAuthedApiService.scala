package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service._
import org.slf4j.LoggerFactory
import spray.http.{ContentTypes, OAuth2BearerToken}
import spray.routing._

import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Created by dvoet on 11/16/16.
 */
trait CookieAuthedApiService extends HttpService with PerRequestCreator with FireCloudDirectives
  with FireCloudRequestBuilding with StreamingActorCreator {

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  def cookieAuthedRoutes: Route =
    // download "proxy" for TSV files
    path("workspaces" / Segment / Segment/ "entities" / Segment/ "tsv") {
      (workspaceNamespace, workspaceName, entityType) =>
        formFields('FCtoken, 'attributeNames.?) { (tokenValue, attributeNamesString) =>
          post { requestContext =>
            val filename = entityType + ".tsv"
            val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
            val userInfo = UserInfo("dummy", OAuth2BearerToken(tokenValue), -1, "dummy")

            val actorProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, userInfo)
            val streamOperation = ExportEntitiesByTypeActor.StreamEntities(requestContext, workspaceNamespace, workspaceName, filename, entityType, attributeNames)
            val actor = actorRefFactory.actorOf(actorProps)
            implicit val timeout = Timeout(5 minute)
            val streamFuture = (actor ? streamOperation).mapTo[Stream[String]]
            streamFuture.map { stream =>
              val streamProps: Props = propsFromString(
                requestContext,
                ContentTypes.`text/plain`,
                stream
              )
              actorRefFactory.actorOf(streamProps)
            }
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
