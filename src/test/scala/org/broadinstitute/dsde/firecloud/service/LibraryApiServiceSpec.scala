package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.DUOS.Consent
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.webservice.LibraryApiService
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.scalatest.BeforeAndAfterEach
import spray.http.StatusCodes._
import spray.http._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.JavaConverters._


class LibraryApiServiceSpec extends BaseServiceSpec with LibraryApiService with BeforeAndAfterEach {

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
  private final val duosConsentOrspIdPath = "/api/duos/consent/orsp/"

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

  override def beforeEach(): Unit = {
    searchDao.reset
  }

  override def afterEach(): Unit = {
    searchDao.reset
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
      "POST as writer on " + publishedPath() - {
        "should be Forbidden for unpublished dataset" in {
          new RequestBuilder(HttpMethods.POST)(publishedPath("unpublishedwriter")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(Forbidden)
          }
        }
      }
      "POST as writer on " + publishedPath() - {
        "should be OK for published dataset" in {
          new RequestBuilder(HttpMethods.POST)(publishedPath("publishedwriter")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
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
          new RequestBuilder(HttpMethods.POST)(publishedPath()) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.indexDocumentInvoked, "indexDocument should have been invoked")
            assert(!this.searchDao.deleteDocumentInvoked, "deleteDocument should not have been invoked")
          }
        }
      }
      "DELETE on " + publishedPath() - {
        "should invoke deleteDocument" in {
          new RequestBuilder(HttpMethods.DELETE)(publishedPath()) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.deleteDocumentInvoked, "deleteDocument should have been invoked")
            assert(!this.searchDao.indexDocumentInvoked, "indexDocument should not have been invoked")
          }
        }
      }
    }
    "when updating fields for a published workspace" - {
      "should republish the workspace" in {
        val content = HttpEntity(ContentTypes.`application/json`, testLibraryMetadata)
        new RequestBuilder(HttpMethods.PUT)(setMetadataPath("publishedwriter"), content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
          assert(this.searchDao.indexDocumentInvoked, "indexDocument should have been invoked")
        }
      }
      "should be forbidden when reader" in {
        new RequestBuilder(HttpMethods.POST)(publishedPath("publishedreader")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(Forbidden)
        }
      }
      "should be allowed when writer" in {
        new RequestBuilder(HttpMethods.POST)(publishedPath("publishedwriter")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when retrieving datasets" - {
      "POST with no searchterm on " + librarySearchPath - {
        "should retrieve all datasets" in {
          val content = HttpEntity(ContentTypes.`application/json`, "{}")
          new RequestBuilder(HttpMethods.POST)(librarySearchPath, content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.findDocumentsInvoked, "findDocuments should have been invoked")
          }
        }
      }
      "POST on " + librarySearchPath - {
        "should search for datasets" in {
          val content = HttpEntity(ContentTypes.`application/json`, "{\"searchTerm\":\"test\", \"from\":0, \"size\":10}")
          new RequestBuilder(HttpMethods.POST)(librarySearchPath, content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.findDocumentsInvoked, "findDocuments should have been invoked")
            val respdata = response.entity.asString.parseJson.convertTo[LibrarySearchResponse]
            assert(respdata.total == 0, "total results should be 0")
            assert(respdata.results.isEmpty, "results array should be empty")
          }
        }
      }
      "POST on " + librarySuggestPath - {
        "should return autcomplete suggestions" in {
          val content = HttpEntity(ContentTypes.`application/json`, "{\"searchTerm\":\"test\", \"from\":0, \"size\":10}")
          new RequestBuilder(HttpMethods.POST)(librarySuggestPath, content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.autocompleteInvoked, "autocompleteInvoked should have been invoked")
            val respdata = response.entity.asString.parseJson.convertTo[LibrarySearchResponse]
            assert(respdata.total == 0, "total results should be 0")
            assert(respdata.results.isEmpty, "results array should be empty")
          }
        }
      }
      "GET on " + libraryPopulateSuggestPath - {
        "should return autcomplete suggestions" in {
          new RequestBuilder(HttpMethods.GET)(libraryPopulateSuggestPath + "library:datasetOwner?q=aha") ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.populateSuggestInvoked, "populateSuggestInvoked should have been invoked")
            val respdata = response.entity.asString
            assert(respdata.contains("library:datasetOwner"))
            assert(respdata.contains("aha"))
          }
        }
      }
      "GET on " + libraryGroupsPath - {
        "should return the all broad users group" in {
          new RequestBuilder(HttpMethods.GET)(libraryGroupsPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            val respdata = response.entity.asString.parseJson.convertTo[Seq[String]]
            assert(respdata.toSet ==  FireCloudConfig.ElasticSearch.discoverGroupNames.asScala.toSet)
          }
        }
      }
    }

    "when searching for ORSP IDs" - {
      "GET on " + duosConsentOrspIdPath - {
        "should return a valid consent for '12345'" in {
          Get(duosConsentOrspIdPath + "12345") ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            val consent = response.entity.asString.parseJson.convertTo[Consent]
            consent shouldNot equal(None)
            consent.name should equal("12345")
          }
        }
        "should return a Bad Request error on 'unapproved'" in {
          Get(duosConsentOrspIdPath + "unapproved") ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(BadRequest)
          }
        }
        "should return a Not Found error on known 'missing'" in {
          Get(duosConsentOrspIdPath + "missing") ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(NotFound)
          }
        }
        "should return a Not Found error on unknown missing" in {
          val orspId = randomStringFromCharList(10, ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9'))
          Get(duosConsentOrspIdPath + orspId) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(NotFound)
          }
        }
      }
    }
  }
}
