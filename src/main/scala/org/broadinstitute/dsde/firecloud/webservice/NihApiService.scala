package org.broadinstitute.dsde.firecloud.webservice

import authentikat.jwt._
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, NihService, NihServiceActor, PerRequestCreator}
import org.broadinstitute.dsde.firecloud.utils.{DateUtils, StandardUserInfoDirectives}
import org.slf4j.LoggerFactory
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.routing._

trait NihApiService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  val nihServiceConstructor: () => NihServiceActor

  val syncRoute: Route =
    path("sync_whitelist" / Segment) { whitelistName =>
      post { requestContext =>
        perRequest(requestContext, NihService.props(nihServiceConstructor),
          NihService.SyncWhitelist(whitelistName))
      }
    } ~ path("sync_whitelist") {
      post { requestContext =>
        perRequest(requestContext, NihService.props(nihServiceConstructor),
          NihService.SyncAllWhitelists)
      }
    }

  val nihRoutes: Route =
    requireUserInfo() { userInfo =>
      pathPrefix("nih") {
        // api/nih/callback: accept JWT, update linkage + lastlogin
        path("callback") {
          post {
            entity(as[JWTWrapper]) { jwtWrapper => requestContext =>

              // get the token from the json wrapper
              val jwt = jwtWrapper.jwt

              // validate the token
              val isValid = JsonWebToken.validate(jwt, FireCloudConfig.Shibboleth.signingKey)

              if (!isValid) {
                requestContext.complete(StatusCodes.BadRequest)
              } else {
                // the NIH JWT is nonstandard. The claims portion of the token *should* be json, but is in fact
                // a simple string. So, libraries tend to fail when working with it. Extract it manually.
                // we shouldn't have to check for bad/missing parts of the token, because we've already validated it.
                val claim = jwt.split("\\.")(1)

                // decode it
                val decoded = java.util.Base64.getDecoder.decode(claim)

                // the entirety of the claims portion of the jwt is the NIH username.
                val linkedNihUsername = new String(decoded)
                // JWT standard uses epoch time for dates, so we'll follow that convention here.
                val linkExpireTime = DateUtils.nowPlus30Days

                val nihLink = NihLink(linkedNihUsername, linkExpireTime)

                // update this user's dbGaP access in rawls, assuming the user exists in the whitelist
                // and save the NIH link keys into Thurloe
                perRequest(requestContext, NihService.props(nihServiceConstructor),
                  NihService.UpdateNihLinkAndSyncSelf(userInfo, nihLink))

              }
            }
          }
        } ~
        path ("status") { requestContext =>
          perRequest(requestContext, NihService.props(nihServiceConstructor),
            NihService.GetNihStatus(userInfo)
          )
        }
      }
    }
}
