package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.{LibrarySearchResponse, UserInfo}
import org.broadinstitute.dsde.firecloud.webservice.LibraryApiService
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import spray.http._
import spray.http.StatusCodes._
import spray.json._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._


class LibraryApiServiceSpec extends BaseServiceSpec with LibraryApiService {

  def actorRefFactory = system
  var workspaceServer: ClientAndServer = _

  lazy val isCuratorPath = "/api/library/user/role/curator"
  private def publishedPath(ns:String="namespace", name:String="name") =
    "/api/library/%s/%s/published".format(ns, name)
  private final val librarySearchPath = "/api/library/search"

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
      "POST as reader on " + publishedPath() - {
        "should be Forbidden" in {
          new RequestBuilder(HttpMethods.POST)(publishedPath("reader")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(Forbidden)
          }
        }
      }
      "POST as owner on " + publishedPath() - {
        "should be OK" in {
          new RequestBuilder(HttpMethods.POST)(publishedPath()) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
          }
        }
      }
      "POST as project_owner on " + publishedPath() - {
        "should be OK" in {
          new RequestBuilder(HttpMethods.POST)(publishedPath("projectowner")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
          }
        }
      }
      "POST on " + publishedPath() - {
        "should invoke indexDocument" in {
          this.searchDAO.asInstanceOf[MockSearchDAO].indexDocumentInvoked = false
          new RequestBuilder(HttpMethods.POST)(publishedPath()) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDAO.asInstanceOf[MockSearchDAO].indexDocumentInvoked, "indexDocument should have been invoked")
            assert(this.searchDAO.asInstanceOf[MockSearchDAO].deleteDocumentInvoked == false, "deleteDocument should not have been invoked")
            this.searchDAO.asInstanceOf[MockSearchDAO].indexDocumentInvoked = false
          }
        }
      }
      "DELETE on " + publishedPath() - {
        "should invoke deleteDocument" in {
          this.searchDAO.asInstanceOf[MockSearchDAO].deleteDocumentInvoked = false
          new RequestBuilder(HttpMethods.DELETE)(publishedPath()) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDAO.asInstanceOf[MockSearchDAO].deleteDocumentInvoked, "deleteDocument should have been invoked")
            assert(this.searchDAO.asInstanceOf[MockSearchDAO].indexDocumentInvoked == false, "indexDocument should not have been invoked")
            this.searchDAO.asInstanceOf[MockSearchDAO].deleteDocumentInvoked = false
          }
        }
      }
    }

    "when retrieving datasets" - {
      "Post with no searchterm on " + librarySearchPath - {
        "should retrieve all datasets" in {
          this.searchDAO.asInstanceOf[MockSearchDAO].findDocumentsInvoked = false
          val content = HttpEntity(ContentTypes.`application/json`, "{}")
          new RequestBuilder(HttpMethods.POST)(librarySearchPath, content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDAO.asInstanceOf[MockSearchDAO].findDocumentsInvoked, "findDocuments should have been invoked")
            this.searchDAO.asInstanceOf[MockSearchDAO].findDocumentsInvoked = false
          }
        }
      }
      "POST on " + librarySearchPath - {
        "should search for datasets" in {
          this.searchDAO.asInstanceOf[MockSearchDAO].findDocumentsInvoked = false
          val content = HttpEntity(ContentTypes.`application/json`, "{\"searchTerm\":\"test\", \"from\":0, \"size\":10}")
          new RequestBuilder(HttpMethods.POST)(librarySearchPath, content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDAO.asInstanceOf[MockSearchDAO].findDocumentsInvoked, "findDocuments should have been invoked")
            val respdata = response.entity.asString.parseJson.convertTo[LibrarySearchResponse]
            assert(respdata.total == 0, "total results should be 0")
            assert(respdata.results.size == 0, "results array should be empty")
            this.searchDAO.asInstanceOf[MockSearchDAO].findDocumentsInvoked = false
          }
        }
      }
    }
  }
}
