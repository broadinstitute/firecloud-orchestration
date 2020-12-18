package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.NihService
import org.broadinstitute.dsde.firecloud.utils.{DateUtils, StandardUserInfoDirectives}
import org.slf4j.LoggerFactory
import pdi.jwt.{Jwt, JwtAlgorithm}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

trait NihApiService extends Directives with RequestBuilding with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext
  lazy val log = LoggerFactory.getLogger(getClass)

  val nihServiceConstructor: () => NihService

  val syncRoute: Route =
    path("sync_whitelist" / Segment) { whitelistName =>
      post {
        complete { nihServiceConstructor().SyncWhitelist(whitelistName) }
      }
    } ~ path("sync_whitelist") {
      post {
        complete { nihServiceConstructor().SyncAllWhitelists }
      }
    }

  val nihRoutes: Route =
    requireUserInfo() { userInfo =>
      pathPrefix("nih") {
        // api/nih/callback: accept JWT, update linkage + lastlogin
        path("callback") {
          post {
            entity(as[JWTWrapper]) { jwtWrapper =>

              // get the token from the json wrapper
              val jwt = jwtWrapper.jwt

              // validate the token.
              // the NIH JWT is nonstandard. The claims portion of the token *should* be json, but is in fact
              // a simple string. So, use decodeRaw here:
              val decodeAttempt = Jwt.decodeRawAll(jwt, FireCloudConfig.Shibboleth.signingKey, Seq(JwtAlgorithm.HS256))

              decodeAttempt match {
                case Success((_, linkedNihUsername, _)) =>
                  // The entirety of the claims portion of the jwt is the NIH username.

                  // JWT standard uses epoch time for dates, so we'll follow that convention here.
                  val linkExpireTime = DateUtils.nowPlus30Days

                  val nihLink = NihLink(linkedNihUsername, linkExpireTime)

                  // update this user's dbGaP access in rawls, assuming the user exists in the whitelist
                  // and save the NIH link keys into Thurloe
                  complete { nihServiceConstructor().UpdateNihLinkAndSyncSelf(userInfo, nihLink) }
                case Failure(_) =>
                  // The exception's error message contains the raw JWT. For an abundance of security, don't
                  // log the error message - even though if we reached this point, the JWT is invalid. It could
                  // still contain sensitive info.
                  log.error(s"Failed to decode JWT")
                  complete { StatusCodes.BadRequest }
                case _ =>
                  // this should never happen: decodeAttempt returned a type it shouldn't.
                  log.error("Unexpected error decoding JWT")
                  complete { StatusCodes.BadRequest }
              }
            }
          }
        } ~
          path ("status") {
            complete { nihServiceConstructor().GetNihStatus(userInfo) }
          }
      }
    }
}
