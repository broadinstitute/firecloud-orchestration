package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import authentikat.jwt._
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.{ProfileClient, ProfileClientActor}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, NihService, PerRequestCreator}
import org.broadinstitute.dsde.firecloud.utils.{DateUtils, StandardUserInfoDirectives}
import org.slf4j.LoggerFactory
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.routing._

trait ProfileApiService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  val profileRoutes: Route =
    pathPrefix("register") {
      path("profile") {
        post {
          requireUserInfo() { userInfo =>
            entity(as[BasicProfile]) { basicProfile =>
              complete("Hi there.")
            }
          }
        }
      }
    }
}
