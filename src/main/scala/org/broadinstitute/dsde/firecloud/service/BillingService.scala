package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.slf4j.LoggerFactory
import spray.http.HttpMethods
import spray.routing._

object BillingService {
  val billingPath = FireCloudConfig.Rawls.authPrefix + "/billing"
  val billingUrl = FireCloudConfig.Rawls.baseUrl + billingPath

}

trait BillingService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)
  lazy val rawlsBillingsRoot = FireCloudConfig.Rawls.authUrl + "/billing"

  val routes: Route =
    pathPrefix("billing") {
      passthrough(rawlsBillingsRoot, HttpMethods.POST)
    } ~
    pathPrefix("billing" / RestPath) { restPath =>
      passthrough(s"$rawlsBillingsRoot/${restPath.toString}", HttpMethods.GET, HttpMethods.DELETE, HttpMethods.POST, HttpMethods.PUT)
    }
}
