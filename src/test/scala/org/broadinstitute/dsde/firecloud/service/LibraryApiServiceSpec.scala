package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AddUpdateAttribute
import org.broadinstitute.dsde.firecloud.model._
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
  private def setMetadataPath(ns: String = "republish", name: String = "name") =
    "/api/library/%s/%s/metadata".format(ns, name)
  private final val librarySearchPath = "/api/library/search"
  private final val librarySuggestPath = "/api/library/suggest"
  private final val libraryPopulateSuggestPath = "/api/library/populate/suggest/"
  private final val libraryGroupsPath = "/api/library/groups"

  val libraryServiceConstructor: (UserInfo) => LibraryService = LibraryService.constructor(app)

  val testLibraryMetadata =
    """
      |{
      |  "description" : "some description",
      |  "userAttributeOne" : "one",
      |  "userAttributeTwo" : "two",
      |  "library:datasetName" : "name",
      |  "library:datasetVersion" : "v1.0",
      |  "library:datasetDescription" : "desc",
      |  "library:datasetCustodian" : "cust",
      |  "library:datasetDepositor" : "depo",
      |  "library:contactEmail" : "name@example.com",
      |  "library:datasetOwner" : "owner",
      |  "library:institute" : ["inst","it","ute"],
      |  "library:indication" : "indic",
      |  "library:numSubjects" : 123,
      |  "library:projectName" : "proj",
      |  "library:datatype" : ["data","type"],
      |  "library:dataCategory" : ["data","category"],
      |  "library:dataUseRestriction" : "dur",
      |  "library:studyDesign" : "study",
      |  "library:cellType" : "cell",
      |  "library:requiresExternalApproval" : false,
      |  "library:useLimitationOption" : "orsp",
      |  "library:technology" : ["is an optional","array attribute"],
      |  "library:orsp" : "some orsp",
      |  "_discoverableByGroups" : ["Group1","Group2"]
      |}
    """.stripMargin

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
          this.searchDao.indexDocumentInvoked = false
          new RequestBuilder(HttpMethods.POST)(publishedPath()) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.indexDocumentInvoked, "indexDocument should have been invoked")
            assert(this.searchDao.deleteDocumentInvoked == false, "deleteDocument should not have been invoked")
            this.searchDao.indexDocumentInvoked = false
          }
        }
      }
      "DELETE on " + publishedPath() - {
        "should invoke deleteDocument" in {
          this.searchDao.deleteDocumentInvoked = false
          new RequestBuilder(HttpMethods.DELETE)(publishedPath()) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.deleteDocumentInvoked, "deleteDocument should have been invoked")
            assert(this.searchDao.indexDocumentInvoked == false, "indexDocument should not have been invoked")
            this.searchDao.deleteDocumentInvoked = false
          }
        }
      }
    }
    "when updating fields for a published workspace" - {
      "should republish the workspace" in {
        this.searchDao.indexDocumentInvoked = false
        val content = HttpEntity(ContentTypes.`application/json`, testLibraryMetadata)
        new RequestBuilder(HttpMethods.PUT)(setMetadataPath(), content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
          assert(this.searchDao.indexDocumentInvoked, "indexDocument should have been invoked")
          this.searchDao.indexDocumentInvoked = false
        }
      }
    }

    "when retrieving datasets" - {
      "POST with no searchterm on " + librarySearchPath - {
        "should retrieve all datasets" in {
          this.searchDao.findDocumentsInvoked = false
          val content = HttpEntity(ContentTypes.`application/json`, "{}")
          new RequestBuilder(HttpMethods.POST)(librarySearchPath, content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.findDocumentsInvoked, "findDocuments should have been invoked")
            this.searchDao.findDocumentsInvoked = false
          }
        }
      }
      "POST on " + librarySearchPath - {
        "should search for datasets" in {
          this.searchDao.findDocumentsInvoked = false
          val content = HttpEntity(ContentTypes.`application/json`, "{\"searchTerm\":\"test\", \"from\":0, \"size\":10}")
          new RequestBuilder(HttpMethods.POST)(librarySearchPath, content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.findDocumentsInvoked, "findDocuments should have been invoked")
            val respdata = response.entity.asString.parseJson.convertTo[LibrarySearchResponse]
            assert(respdata.total == 0, "total results should be 0")
            assert(respdata.results.size == 0, "results array should be empty")
            this.searchDao.findDocumentsInvoked = false
          }
        }
      }
      "POST on " + librarySuggestPath - {
        "should return autcomplete suggestions" in {
          this.searchDao.autocompleteInvoked = false
          val content = HttpEntity(ContentTypes.`application/json`, "{\"searchTerm\":\"test\", \"from\":0, \"size\":10}")
          new RequestBuilder(HttpMethods.POST)(librarySuggestPath, content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.autocompleteInvoked, "autocompleteInvoked should have been invoked")
            val respdata = response.entity.asString.parseJson.convertTo[LibrarySearchResponse]
            assert(respdata.total == 0, "total results should be 0")
            assert(respdata.results.size == 0, "results array should be empty")
            this.searchDao.autocompleteInvoked = false
          }
        }
      }
      "GET on " + libraryPopulateSuggestPath - {
        "should return autcomplete suggestions" in {
          this.searchDao.populateSuggestInvoked = false
          new RequestBuilder(HttpMethods.GET)(libraryPopulateSuggestPath + "library:datasetOwner?q=aha") ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.populateSuggestInvoked, "populateSuggestInvoked should have been invoked")
            val respdata = response.entity.asString
            assert(respdata.contains("library:datasetOwner"))
            assert(respdata.contains("aha"))
            this.searchDao.populateSuggestInvoked = false
          }
        }
      }
      "GET on " + libraryGroupsPath - {
        "should return the all broad users group" in {
          new RequestBuilder(HttpMethods.GET)(libraryGroupsPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            val respdata = response.entity.asString
            assert(respdata.contains("all_broad_users"))
          }
        }
      }
    }
  }
}
