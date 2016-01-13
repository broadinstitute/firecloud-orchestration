package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.core.NIHWhitelistUtils
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import spray.http.HttpMethods
import spray.http.StatusCodes._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class NIHSyncEndpointsSpec extends ServiceSpec with NIHSyncService {

  def actorRefFactory = system


  val syncWhitelistPath = "/sync_whitelist"

  "NIHSyncEndpointsSpec" - {

    "when calling the sync whitelist service" - {

        s"POST on $syncWhitelistPath" - {
          "should not receive a MethodNotAllowed" in {
            Post(syncWhitelistPath) ~> sealRoute(routes) ~> check {
              status shouldNot equal(MethodNotAllowed)
            }
          }
        }

        s"GET, PUT, DELETE on $syncWhitelistPath" - {
          "should receive a MethodNotAllowed" in {
            List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.DELETE) map {
              method =>
                new RequestBuilder(method)(syncWhitelistPath) ~> sealRoute(routes) ~> check {
                  status should equal(MethodNotAllowed)
                }
            }
          }
        }
      }
    }

}
