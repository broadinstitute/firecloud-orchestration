package org.broadinstitute.dsde.firecloud.service

import akka.actor.{ActorRefFactory, Props}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core._
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.OAuthUser
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http._
import spray.http.StatusCodes._
import spray.routing._

import scala.concurrent.Future
import scala.util.Try

trait CookieAuthedService extends HttpService with PerRequestCreator with FireCloudDirectives
  with FireCloudRequestBuilding {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  def routes: Route =
    // download "proxy" for TSV files
    path("workspaces" / Segment / Segment/ "entities" / Segment/ "tsv") {
      (workspaceNamespace, workspaceName, entityType) =>
        cookie("FCtoken") { tokenCookie =>
          mapRequest(r => addCredentials(OAuth2BearerToken(tokenCookie.content)).apply(r)) { requestContext =>
            val baseRawlsEntitiesUrl = FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)
            val filename = entityType + ".txt"
            perRequest(requestContext, Props(new ExportEntitiesByTypeActor(requestContext)),
              ExportEntitiesByType.ProcessEntities(baseRawlsEntitiesUrl, filename, entityType))
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
