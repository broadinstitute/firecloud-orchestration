package org.broadinstitute.dsde.firecloud.service

import java.net.URL

import akka.actor.Props
import org.broadinstitute.dsde.firecloud.{Application, FireCloudServiceActor}
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
  trait ActorRefFactoryContext {
    def actorRefFactory = system
  }
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

    "when handling routes" - {
      "should do the right thing" in {
        actorRefFactory.actorOf(Props[FireCloudServiceActor])
        val methodsService = new MethodsService with ActorRefFactoryContext
        val oAuthService = new OAuthService with ActorRefFactoryContext
        val nihSyncService = new NIHSyncService with ActorRefFactoryContext
        val routes = methodsService.routes ~ oAuthService.routes ~ nihSyncService.routes
        new RequestBuilder(HttpMethods.GET)("/handle-oauth-code") ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
        }
        new RequestBuilder(HttpMethods.GET)("/handle-oauth-codes") ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
        }
      }
    }

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
