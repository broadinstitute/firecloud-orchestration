package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.ErrorReport
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FreeSpec, Matchers}
import spray.http.StatusCode
import spray.httpx.SprayJsonSupport._
import spray.testkit.ScalatestRouteTest

// common Service Spec to be inherited by service tests
trait ServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest with Matchers with FireCloudRequestBuilding {

  // is the response an ErrorReport with the given Source and StatusCode
  def errorReportCheck(source: String, statusCode: StatusCode) = {
    val report = responseAs[ErrorReport]
    report.source should be(source)
    report.httpStatusCode.get should be(statusCode)
  }

}
