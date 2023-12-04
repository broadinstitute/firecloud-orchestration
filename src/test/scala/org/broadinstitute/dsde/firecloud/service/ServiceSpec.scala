package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.TestRequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.TestKitBase

import scala.concurrent.duration._

// common Service Spec to be inherited by service tests
trait ServiceSpec extends AnyFreeSpec with ScalaFutures with ScalatestRouteTest with Matchers with TestRequestBuilding with TestKitBase {

  implicit val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(5.seconds)

  val allHttpMethods = Seq(CONNECT, DELETE, GET, HEAD, PATCH, POST, PUT, TRACE)

  def allHttpMethodsExcept(method: HttpMethod, methods: HttpMethod*): Seq[HttpMethod] = allHttpMethodsExcept(method +: methods)
  def allHttpMethodsExcept(methods: Seq[HttpMethod]): Seq[HttpMethod] = allHttpMethods.diff(methods)

  // is the response an ErrorReport with the given Source and StatusCode
  def errorReportCheck(source: String, statusCode: StatusCode): Unit = {
    val report = responseAs[ErrorReport]
    report.source should be(source)
    report.statusCode.get should be(statusCode)
  }

  def checkIfPassedThrough(route: Route, method: HttpMethod, uri: String, toBeHandled: Boolean): Unit = {
    new RequestBuilder(method)(uri) ~> dummyAuthHeaders ~> route ~> check {
      handled should be(toBeHandled)
    }
  }
}
