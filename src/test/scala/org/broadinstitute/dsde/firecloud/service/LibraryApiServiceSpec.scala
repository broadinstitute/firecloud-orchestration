package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.MockRawlsDAO
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.DUOS.{Consent, ConsentError}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.webservice.LibraryApiService
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.{AttributeFormat, AttributeName, AttributeString, PlainArrayAttributeListSerializer}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.scalatest.BeforeAndAfterEach
import spray.http.StatusCodes._
import spray.http._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.httpx.SprayJsonSupport._

import scala.collection.JavaConverters._


class LibraryApiServiceSpec extends BaseServiceSpec with LibraryApiService with BeforeAndAfterEach {

  def actorRefFactory = system
  var consentServer: ClientAndServer = _

  lazy val isCuratorPath = "/api/library/user/role/curator"
  private def publishedPath(ns:String="namespace", name:String="name") =
    "/api/library/%s/%s/published".format(ns, name)
  private def setMetadataPath(ns: String = "republish", name: String = "name") =
    "/api/library/%s/%s/metadata".format(ns, name)
  private def setDiscoverableGroupsPath(ns: String = "discoverableGroups", name: String = "name") =
    "/api/library/%s/%s/discoverableGroups".format(ns, name)
  private final val librarySearchPath = "/api/library/search"
  private final val librarySuggestPath = "/api/library/suggest"
  private final val libraryPopulateSuggestPath = "/api/library/populate/suggest/"
  private final val libraryGroupsPath = "/api/library/groups"
  private def duosConsentOrspIdPath(orspId: String): String = "/api/duos/consent/orsp/%s".format(orspId)

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

  val incompleteMetadata =
    """
      |{
      |  "userAttributeOne" : "one",
      |  "userAttributeTwo" : "two",
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
    consentServer = startClientAndServer(consentServerPort)

    val consentPath = consentUrl.replace(FireCloudConfig.Duos.baseConsentUrl, "")
    val consent = Consent(consentId = "consent-id-12345", name = "12345", translatedUseRestriction = Some("Translation"))
    val consentError = ConsentError(message = "Unapproved", code = BadRequest.intValue)
    val consentNotFound = ConsentError(message = "Not Found", code = NotFound.intValue)

    val okGet = request().withMethod("GET").withPath(consentPath).withHeader(authHeader).withQueryStringParameter("name", "12345")
    val okResponse = org.mockserver.model.HttpResponse.response().withHeaders(MockUtils.header).withStatusCode(OK.intValue).withBody(consent.toJson.prettyPrint)
    consentServer.when(okGet).respond(okResponse)

    val badRequestGet = request().withMethod("GET").withPath(consentPath).withHeader(authHeader).withQueryStringParameter("name", "unapproved")
    val badRequestResponse = org.mockserver.model.HttpResponse.response().withHeaders(MockUtils.header).withStatusCode(BadRequest.intValue).withBody(consentError.toJson.prettyPrint)
    consentServer.when(badRequestGet).respond(badRequestResponse)

    val notFoundGet = request().withMethod("GET").withPath(consentPath).withHeader(authHeader).withQueryStringParameter("name", "missing")
    val notFoundResponse = org.mockserver.model.HttpResponse.response().withHeaders(MockUtils.header).withStatusCode(NotFound.intValue).withBody(consentNotFound.toJson.prettyPrint)
    consentServer.when(notFoundGet).respond(notFoundResponse)
  }

  override def afterAll(): Unit = {
    consentServer.stop()
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

    "when saving metadata" - {
      "complete data can be saved for an unpublished workspace" in {
          val content = HttpEntity(ContentTypes.`application/json`, testLibraryMetadata)
          new RequestBuilder(HttpMethods.PUT)(setMetadataPath("unpublishedwriter"), content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
          }
        }
      "incomplete data can be saved for an unpublished workspace" in {
        val content = HttpEntity(ContentTypes.`application/json`, incompleteMetadata)
        new RequestBuilder(HttpMethods.PUT)(setMetadataPath("unpublishedwriter"), content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
        }
      }

      "complete data can be saved for a published workspace" in {
        val content = HttpEntity(ContentTypes.`application/json`, testLibraryMetadata)
        new RequestBuilder(HttpMethods.PUT)(setMetadataPath("publishedwriter"), content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
        }
      }

      "cannot save incomplete data if already published dataset" in {
        val content = HttpEntity(ContentTypes.`application/json`, incompleteMetadata)
        new RequestBuilder(HttpMethods.PUT)(setMetadataPath("publishedwriter"), content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(BadRequest)
        }
      }

      "validates for unpublished dataset if user specifies validate=true" in {
        val content = HttpEntity(ContentTypes.`application/json`, incompleteMetadata)
        new RequestBuilder(HttpMethods.PUT)(setMetadataPath("unpublishedwriter") + "?validate=true", content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(BadRequest)
        }
      }

      "validation defaults to false if user specifies a non-boolean value" in {
        val content = HttpEntity(ContentTypes.`application/json`, incompleteMetadata)
        new RequestBuilder(HttpMethods.PUT)(setMetadataPath("unpublishedwriter") + "?validate=cat", content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
        }
      }

      "always validates for published workspace even if user specifies validate=false" in {
        val content = HttpEntity(ContentTypes.`application/json`, incompleteMetadata)
        new RequestBuilder(HttpMethods.PUT)(setMetadataPath("publishedwriter") + "?validate=false", content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(BadRequest)
        }
      }

      "always validates for published workspace even if user specifies a non-boolean value" in {
        val content = HttpEntity(ContentTypes.`application/json`, incompleteMetadata)
        new RequestBuilder(HttpMethods.PUT)(setMetadataPath("publishedwriter") + "?validate=cat", content) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(BadRequest)
        }
      }

    }

    "when getting metadata" - {
      // make sure we're using the right deserializer for this block of tests
      implicit val attributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer

      "will return just library attrs if the workspace has multiple attribute namespaces" in {
        Get(setMetadataPath("publishedowner")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
          val meta = responseAs[AttributeMap]
          // see MockRawlsDAO.publishedRawlsWorkspaceWithAttributes
          val expected:AttributeMap = Map( AttributeName("library", "projectName") -> AttributeString("testing") )
          assertResult(expected) {meta}
        }
      }
      "complete data can be retrieved for a valid workspace" in {
        Get(setMetadataPath("libraryValid")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
          val meta = responseAs[AttributeMap]
          val expected = new MockRawlsDAO().unpublishedRawlsWorkspaceLibraryValid.attributes
          assertResult (expected) {meta}
        }
      }
      "will return empty set if no metadata exists" in {
        Get(setMetadataPath("attributes")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
          val meta = responseAs[AttributeMap]
          assert(meta.isEmpty)
        }
      }
    }

    "when calling publish" - {
      "POST on " + publishedPath() - {
        "should return No Content for already published workspace " in {
          new RequestBuilder(HttpMethods.POST)(publishedPath("publishedwriter")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(NoContent)
          }
        }
        "should return OK and invoke indexDocument for unpublished workspace with valid dataset" in {
          new RequestBuilder(HttpMethods.POST)(publishedPath("libraryValid")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.indexDocumentInvoked, "indexDocument should have been invoked")
            assert(!this.searchDao.deleteDocumentInvoked, "deleteDocument should not have been invoked")
          }
        }
        "should return BadRequest and not invoke indexDocument for unpublished workspace with invalid dataset" in {
          new RequestBuilder(HttpMethods.POST)(publishedPath()) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(BadRequest)
            assert(!this.searchDao.indexDocumentInvoked, "indexDocument should not have been invoked")
            assert(!this.searchDao.deleteDocumentInvoked, "deleteDocument should not have been invoked")
          }
        }
      }
      "DELETE on " + publishedPath() - {
        "should be No Content for unpublished workspace" in {
          new RequestBuilder(HttpMethods.DELETE)(publishedPath("unpublishedwriter")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(NoContent)
          }
        }
        "as return OK and invoke deleteDocument for published workspace" in {
          new RequestBuilder(HttpMethods.DELETE)(publishedPath("publishedowner")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            assert(this.searchDao.deleteDocumentInvoked, "deleteDocument should have been invoked")
            assert(!this.searchDao.indexDocumentInvoked, "indexDocument should not have been invoked")
          }
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
      "DELETE, POST, PUT, POST should receive a MethodNotAllowed" in {
        List(HttpMethods.DELETE, HttpMethods.POST, HttpMethods.PUT, HttpMethods.PATCH) map {
          method =>
            new RequestBuilder(method)(duosConsentOrspIdPath("anything")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
      "GET on " + duosConsentOrspIdPath("12345") - {
        "should return a valid consent for '12345'" in {
          Get(duosConsentOrspIdPath("12345")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(OK)
            val consent = response.entity.asString.parseJson.convertTo[Consent]
            consent shouldNot equal(None)
            consent.name should equal("12345")
          }
        }
        "should return a Bad Request error on 'unapproved'" in {
          Get(duosConsentOrspIdPath("unapproved")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(BadRequest)
          }
        }
        "should return a Not Found error on known 'missing'" in {
          Get(duosConsentOrspIdPath("missing")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
            status should equal(NotFound)
          }
        }
      }
    }
    "when working with Library discoverable groups" - {
      "should return the right groups on get" in {
        Get(setDiscoverableGroupsPath("libraryValid","unittest")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
          assertResult(List("group1","group2")) {responseAs[List[String]]}
        }
      }
      "should return an empty array if no groups are assigned" in {
        Get(setDiscoverableGroupsPath("publishedwriter","unittest")) ~> dummyUserIdHeaders("1234") ~> sealRoute(libraryRoutes) ~> check {
          status should equal(OK)
          assertResult(List.empty[String]) {responseAs[List[String]]}
        }
      }
    }
  }
}
