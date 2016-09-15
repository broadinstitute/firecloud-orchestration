package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.parboiled.common.FileUtils
import scala.util.Try
import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.json._
import spray.util._

class LibraryServiceSpec extends ServiceSpec with LibraryService {

  def actorRefFactory = system
  var workspaceServer: ClientAndServer = _

  lazy val isCuratorPath = "/api/library/user/role/curator"

  override def beforeAll(): Unit = {

    workspaceServer = startClientAndServer(workspaceServerPort)
    workspaceServer
      .when(request.withMethod("GET").withPath(isCuratorPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
  }

  "LibraryService" - {

    /* Handle passthrough handlers here */

    "when calling the isCurator endpoint" - {
      "PUT, POST, DELETE on /api/library/user/role/curator" - {
        "should receive a MethodNotAllowed" in {
          List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(isCuratorPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(routes) ~> check {
                System.out.println(response)
                status should equal(MethodNotAllowed)
              }
          }
        }
      }
    }

    "in its schema definition" - {
      "has valid JSON" in {
        val classLoader = actorSystem(actorRefFactory).dynamicAccess.classLoader
        val inputStream = classLoader.getResource("library/attribute-definitions.json").openStream()
        try {
          val fileContents = FileUtils.readAllText(inputStream)
          val jsonVal:Try[JsValue] = Try(fileContents.parseJson)
          assert(jsonVal.isSuccess)
        } finally {
          inputStream.close()
        }
      }
    }



  }




}
