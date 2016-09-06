package org.broadinstitute.dsde.firecloud.service

import akka.actor.Props
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core._
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http._
import spray.http.StatusCodes._
import spray.routing._

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
    // download "proxy" for GCS objects. When using a simple RESTful url to download from GCS, Chrome/GCS will look
    // at all the currently-signed in Google identities for the browser, and pick the "most recent" one. This may
    // not be the one we want to use for downloading the GCS object. To force the identity we want, we send the
    // access token in the Authorization header when downloading the object.
    path("download" / "b" / Segment / "o" / RestPath) { (bucket, obj) =>
        cookie("FCtoken") { tokenCookie =>
          mapRequest(r => addCredentials(OAuth2BearerToken(tokenCookie.content)).apply(r)) { requestContext =>

            // check if the user has access to the file. If so, sign and issue a redirect; if not, replay Google's
            // exception to the user, just as if the user tried to access the Google file directly.
            val extReq = Get( HttpGoogleServicesDAO.getObjectResourceUrl(bucket, obj.toString) )
            val pipeline = authHeaders(requestContext) ~> sendReceive
            pipeline {extReq} map { response =>
                response.status match {
                  case OK =>
                    val redirectUrl = HttpGoogleServicesDAO.getSignedUrl(bucket, obj.toString)
                    requestContext.redirect(redirectUrl, StatusCodes.TemporaryRedirect)
                  case _ => requestContext.complete(response)
              }
            }
          }
        }
    }
}
