package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.NihService
import org.broadinstitute.dsde.firecloud.utils.{EnabledUserDirectives, StandardUserInfoDirectives}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

trait NihApiService extends Directives with RequestBuilding with EnabledUserDirectives with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext
  lazy val log = LoggerFactory.getLogger(getClass)

  val nihServiceConstructor: () => NihService

  val syncRoute: Route =
    path("sync_whitelist" / Segment) { whitelistName =>
      post {
        complete { nihServiceConstructor().syncAllowlistAllUsers(whitelistName) }
      }
    } ~ path("sync_whitelist") {
      post {
        complete { nihServiceConstructor().syncAllNihAllowlistsAllUsers() }
      }
    }

  val nihRoutes: Route =
    requireUserInfo() { userInfo =>
      requireEnabledUser(userInfo) {
        pathPrefix("nih") {
          // api/nih/callback: accept JWT, update linkage + lastlogin
          path("callback") {
            post {
              entity(as[JWTWrapper]) { jwtWrapper =>
                complete { nihServiceConstructor().updateNihLinkAndSyncSelf(userInfo, jwtWrapper) }
              }
            }
          } ~
          path ("status") {
            complete { nihServiceConstructor().getNihStatus(userInfo) }
          } ~
          path ("account") {
            delete {
              complete { nihServiceConstructor().unlinkNihAccountAndSyncSelf(userInfo).map(_ => StatusCodes.NoContent) }
            }
          }
        }
      }
    }
}
