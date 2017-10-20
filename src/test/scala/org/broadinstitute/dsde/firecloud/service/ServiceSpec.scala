package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FreeSpec, Matchers}
import spray.http.HttpMethods._
import spray.http.{HttpMethod, StatusCode}
import spray.httpx.SprayJsonSupport._
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration._

// common Service Spec to be inherited by service tests
trait ServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest with Matchers with FireCloudRequestBuilding {

  implicit val routeTestTimeout = RouteTestTimeout(5.seconds)

  // OPTIONS is not included because we accept all OPTIONS requests to all endpoints for CORS.
  val allHttpMethods = Seq(CONNECT, DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT, TRACE)

  def allHttpMethodsExcept(method: HttpMethod, methods: HttpMethod*): Seq[HttpMethod] = allHttpMethodsExcept(method +: methods)
  def allHttpMethodsExcept(methods: Seq[HttpMethod]): Seq[HttpMethod] = allHttpMethods.diff(methods)

  // is the response an ErrorReport with the given Source and StatusCode
  def errorReportCheck(source: String, statusCode: StatusCode): Unit = {
    val report = responseAs[ErrorReport]
    report.source should be(source)
    report.statusCode.get should be(statusCode)
  }

}
