package org.broadinstitute.dsde.firecloud.service

import java.net.URL

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.webservice.LibraryApiService
import org.everit.json.schema.Schema
import org.everit.json.schema.loader.SchemaLoader
import org.json.{JSONObject, JSONTokener}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.parboiled.common.FileUtils
import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.json._

import scala.util.Try

class OauthApiServiceSpec extends ServiceSpec with OAuthService {

  def actorRefFactory = system
  var workspaceServer: ClientAndServer = _

  lazy val isCuratorPath = "/api/library/user/role/curator"

  val rawlsDAO:RawlsDAO = new MockRawlsDAO
  val searchDAO:SearchDAO = new MockSearchDAO
  val app:Application = new Application(rawlsDAO, searchDAO)
  val libraryServiceConstructor: (UserInfo) => LibraryService = LibraryService.constructor(app)

//  override def beforeAll(): Unit = {
//
//    workspaceServer = startClientAndServer(workspaceServerPort)
//    workspaceServer
//      .when(request.withMethod("GET").withPath(isCuratorPath))
//      .respond(
//        org.mockserver.model.HttpResponse.response()
//          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
//      )
//  }
//
//  override def afterAll(): Unit = {
//    workspaceServer.stop()
//  }

  "OauthService" - {

    /* Handle passthrough handlers here */

    "when calling the /handle-oauth-code endpoint" - {
      "GET, PUT, DELETE" - {
        "should receive a MethodNotAllowed" in {
          List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)("/handle-oauth-code") ~> sealRoute(routes) ~> check {
                System.out.println(response)
                status should equal(MethodNotAllowed)
              }
          }
        }
      }
    }
  }
}
