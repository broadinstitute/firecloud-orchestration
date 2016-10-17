package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.webservice.LibraryApiService
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._

import spray.http.HttpMethods
import spray.http.StatusCodes._

class LibraryApiServiceSpec extends BaseServiceSpec with LibraryApiService {

  def actorRefFactory = system
  var workspaceServer: ClientAndServer = _

  lazy val isCuratorPath = "/api/library/user/role/curator"
  private final val publishedPath = "/api/library/%s/%s/published".format("namespace", "name")

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
    "when calling publish" - {
      "POST on " + publishedPath - {
        "should invoke indexDocument" in {
          this.searchDAO.asInstanceOf[MockSearchDAO].indexDocumentInvoked = false
          new RequestBuilder(HttpMethods.POST)(publishedPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDAO.asInstanceOf[MockSearchDAO].indexDocumentInvoked, "indexDocument should have been invoked")
            assert(this.searchDAO.asInstanceOf[MockSearchDAO].deleteDocumentInvoked == false, "deleteDocument should not have been invoked")
            this.searchDAO.asInstanceOf[MockSearchDAO].indexDocumentInvoked = false
          }
        }
      }
      "DELETE on " + publishedPath - {
        "should invoke deleteDocument" in {
          this.searchDAO.asInstanceOf[MockSearchDAO].deleteDocumentInvoked = false
          new RequestBuilder(HttpMethods.DELETE)(publishedPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDAO.asInstanceOf[MockSearchDAO].deleteDocumentInvoked, "deleteDocument should have been invoked")
            assert(this.searchDAO.asInstanceOf[MockSearchDAO].indexDocumentInvoked ==  false, "indexDocument should not have been invoked")
            this.searchDAO.asInstanceOf[MockSearchDAO].deleteDocumentInvoked = false
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
