package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.SystemStatus
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impSystemStatus
import org.broadinstitute.dsde.firecloud.webservice.StatusApiService
import spray.http.StatusCodes.{OK}
import spray.routing.HttpService
import spray.httpx.SprayJsonSupport._


/**
  * Created by anichols on 4/13/17.
  */
class StatusServiceSpec extends BaseServiceSpec with HttpService with StatusApiService {

  def actorRefFactory = system

  val statusServiceConstructor: () => StatusService = StatusService.constructor(app)

  "StatusService returns 'ok'" in {

    Get("/status") ~> sealRoute(publicStatusRoutes) ~> check {
      status should be(OK)
      val response = responseAs[SystemStatus]
      response.ok should be(true)
      response.systems.size should be(4)
    }

  }

}
