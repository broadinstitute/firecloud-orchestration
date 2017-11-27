package org.broadinstitute.dsde.firecloud.webservice

import java.text.SimpleDateFormat

import org.broadinstitute.dsde.firecloud.service._
import spray.routing._

trait StatusApiService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private implicit val executionContext = actorRefFactory.dispatcher

  val statusServiceConstructor: () => StatusService

  val statusRoutes: Route = {
    path("status") {
      get { requestContext =>
        perRequest(requestContext, StatusService.props(statusServiceConstructor), StatusService.CollectStatusInfo)
      }
    }
  }

}
