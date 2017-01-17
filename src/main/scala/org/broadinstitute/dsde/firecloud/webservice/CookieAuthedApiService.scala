package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service._
import org.slf4j.LoggerFactory
import spray.http.OAuth2BearerToken
import spray.routing._

/**
 * Created by dvoet on 11/16/16.
 */
trait CookieAuthedApiService extends HttpService with PerRequestCreator with FireCloudDirectives
  with FireCloudRequestBuilding {

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
            perRequest(requestContext, ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, userInfo),
              ExportEntitiesByTypeActor.ExportEntities(workspaceNamespace, workspaceName, filename, entityType, attributeNames))
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
