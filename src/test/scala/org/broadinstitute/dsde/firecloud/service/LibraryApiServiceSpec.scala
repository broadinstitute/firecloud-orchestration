package org.broadinstitute.dsde.firecloud.service

import java.net.URL

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.webservice.LibraryApiService
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.parboiled.common.FileUtils

import scala.util.Try
import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.json._
import spray.util._
import org.everit.json.schema.Schema
import org.everit.json.schema.loader.SchemaLoader
import org.json.JSONObject
import org.json.JSONTokener

class LibraryApiServiceSpec extends ServiceSpec with LibraryApiService {

  def actorRefFactory = system
  var workspaceServer: ClientAndServer = _

  lazy val isCuratorPath = "/api/library/user/role/curator"

  val rawlsDAO:RawlsDAO = new MockRawlsDAO
  val searchDAO:SearchDAO = new MockSearchDAO
  val app:Application = new Application(rawlsDAO, searchDAO)
  val libraryServiceConstructor: (UserInfo) => LibraryService = LibraryService.constructor(app)

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
              new RequestBuilder(method)(isCuratorPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
                System.out.println(response)
                status should equal(MethodNotAllowed)
              }
          }
        }
      }
    }

    "in its schema definition" - {
      "has valid JSON" in {
        val fileContents = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val jsonVal:Try[JsValue] = Try(fileContents.parseJson)
        assert(jsonVal.isSuccess, "Schema should be valid json")
      }
      "has valid JSON Schema" in {
        val schemaStream = new URL("http://json-schema.org/draft-04/schema").openStream()

        try {
          val fileContents = FileUtils.readAllTextFromResource("library/attribute-definitions.json")

          val rawSchema:JSONObject = new JSONObject(new JSONTokener(schemaStream))
          val schema:Schema = SchemaLoader.load(rawSchema)
          schema.validate(new JSONObject(fileContents))

        } finally {
          schemaStream.close()
        }
      }
    }

  }
}
