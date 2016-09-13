package org.broadinstitute.dsde.firecloud.service

import akka.actor.Props
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
    // download "proxy" for GCS objects. When using a simple RESTful url to download from GCS, Chrome/GCS will look
    // at all the currently-signed in Google identities for the browser, and pick the "most recent" one. This may
    // not be the one we want to use for downloading the GCS object. To force the identity we want, we jump through
    // some hoops: if we can, we presign a url using a service account.
    // pseudocode:
    //  if (we can determine the user's identity via google)
    //    if (the user has access to the object)
    //      if (the service account has access to the object)
    //        redirect to a signed url that guarantees the user's identity
    //      else
    //        redirect to a direct download in GCS
    path("download" / "b" / Segment / "o" / RestPath) { (bucket, obj) =>
        cookie("FCtoken") { tokenCookie =>
          mapRequest(r => addCredentials(OAuth2BearerToken(tokenCookie.content)).apply(r)) { requestContext =>

            // query Google for the user's info, then check if the user has access to the file.
            // If the user is valid and has access, issue a redirect; if not, replay Google's
            // exception to the user, just as if the user tried to access Google directly.
            val objectStr = s"gs://$bucket/$obj"

            val userReq = Get( HttpGoogleServicesDAO.profileUrl )
            val userPipeline = authHeaders(requestContext) ~> sendReceive
            userPipeline{userReq} map { userResponse =>

              userResponse.status match {
                case OK =>
                  // user is known to Google
                  val objectReq = Get( HttpGoogleServicesDAO.getObjectResourceUrl(bucket, obj.toString) )
                  val objectPipeline = authHeaders(requestContext) ~> sendReceive
                  objectPipeline {objectReq} map { objectResponse =>

                    import spray.json._
                    import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOAuthUser
                    val oauthUser:Try[OAuthUser] = Try(userResponse.entity.asString.parseJson.convertTo[OAuthUser])
                    val userStr = (oauthUser getOrElse userResponse.entity).toString

                    objectResponse.status match {
                      case OK =>
                        // the user has access to the object.
                        // make a final request to the object as the service account, to determine
                        // if we use a signed url or a direct download.
                        val serviceAccountReq = Get( HttpGoogleServicesDAO.getObjectResourceUrl(bucket, obj.toString) )
                        val serviceAccountPipeline = addCredentials(OAuth2BearerToken(HttpGoogleServicesDAO.getRawlsServiceAccountAccessToken)) ~> sendReceive

                        serviceAccountPipeline {serviceAccountReq} map { serviceAccountResponse =>
                          serviceAccountResponse.status match {
                            case OK =>
                              // the service account can read the object too. We are safe to sign a url.
                              log.info(s"$userStr download via signed URL allowed for [$objectStr]")
                              val redirectUrl = HttpGoogleServicesDAO.getSignedUrl(bucket, obj.toString)
                              requestContext.redirect(redirectUrl, StatusCodes.TemporaryRedirect)
                            case _ =>
                              // the service account cannot read the object, even though the user can. We cannot
                              // make a signed url, because the service account won't have permission to sign it.
                              // therefore, we rely on a direct link. We accept that a direct link is vulnerable to
                              // identity problems if the current user is signed in to multiple google identies in
                              // the same browser profile, but this is the best we can do.
                              // generate direct link per https://cloud.google.com/storage/docs/authentication#cookieauth
                              log.info(s"$userStr download via direct link allowed for [$objectStr]")
                              val redirectUrl = s"https://storage.cloud.google.com/$bucket/${obj.toString}"
                              requestContext.redirect(redirectUrl, StatusCodes.TemporaryRedirect)
                          }
                        }
                      case _ =>
                        // the user does not have access to the object.
                        val responseStr = objectResponse.entity.asString.replaceAll("\n","")
                        log.warn(s"$userStr download denied for [$objectStr], because (${objectResponse.status}): $responseStr")
                        requestContext.complete(objectResponse)
                    }
                  }
                case _ =>
                  // Google did not return a profile for this user; abort.
                  log.warn(s"Unknown user attempted download for [$objectStr] and was denied. User info (${userResponse.status}): ${userResponse.entity.asString}")
                  requestContext.complete(userResponse)
              }
            }
          }
        }
    }
}
