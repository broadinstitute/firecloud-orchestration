package org.broadinstitute.dsde.firecloud.service

import akka.actor.Props
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core._
import org.slf4j.LoggerFactory
import spray.http.{HttpCookie, HttpRequest, HttpHeaders, OAuth2BearerToken}
import spray.routing._

trait CookieAuthedService extends HttpService with PerRequestCreator with FireCloudDirectives
  with FireCloudRequestBuilding {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  def routes: Route =
    path("workspaces" / Segment / Segment/ "entities" / Segment/ "tsv") {
      (workspaceNamespace, workspaceName, entityType) =>
        cookie("FCtoken") { tokenCookie =>
          mapRequest(r => addCredentials(OAuth2BearerToken(tokenCookie.content)).apply(r)) { requestContext =>
            val baseRawlsEntitiesUrl = FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)
            val entityTypeUrl = encodeUri(baseRawlsEntitiesUrl + "/" + entityType)
            val filename = entityType + ".txt"
            perRequest(requestContext, Props(new ExportEntitiesByTypeActor(requestContext)),
              ExportEntitiesByType.ProcessEntities(entityTypeUrl, filename, entityType))
          }
        }
    }
}
