package org.broadinstitute.dsde.firecloud.webservice

import java.util.UUID
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route.{seal => sealRoute}

import javax.net.ssl.HttpsURLConnection
import org.apache.commons.io.IOUtils
import org.broadinstitute.dsde.firecloud.dataaccess.ImportServiceFiletypes.{FILETYPE_PFB, FILETYPE_TDR}
import org.broadinstitute.dsde.firecloud.dataaccess.{MockRawlsDAO, MockShareLogDAO, WorkspaceApiServiceSpecShareLogDAO}
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.mock.{MockTSVFormData, MockUtils}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, PermissionReportService, WorkspaceService}
import org.broadinstitute.dsde.firecloud.{EntityService, FireCloudConfig}
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.rawls.model._
import org.joda.time.DateTime
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.mockserver.model.Parameter
import org.mockserver.socket.KeyStoreFactory
import org.scalatest.BeforeAndAfterEach
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext

object WorkspaceApiServiceSpec {

  val publishedWorkspace = WorkspaceDetails(
    "namespace",
    "name-published",
    "workspace_id",
    "buckety_bucket",
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "my_workspace_creator",
    Some(Map(AttributeName("library", "published") -> AttributeBoolean(true))), //attributes
    false, //locked
    Some(Set.empty), //authorizationDomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    None,
    Option(DateTime.now()),
    None,
    None,
    WorkspaceState.Ready
  )

}

class WorkspaceApiServiceSpec extends BaseServiceSpec with WorkspaceApiService with BeforeAndAfterEach with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val workspace = WorkspaceDetails(
    "namespace",
    "name",
    "workspace_id",
    "buckety_bucket",
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "my_workspace_creator",
    Some(Map()), //attributes
    false, //locked
    Some(Set.empty), //authorizationDomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    None,
    Option(DateTime.now()),
    None,
    None,
    WorkspaceState.Ready
  )

  val jobId = "testOp"

  // Mock remote endpoints
  private final val workspacesRoot = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  private final val workspacesPath = workspacesRoot + "/%s/%s".format(workspace.namespace, workspace.name)
  private final val methodconfigsPath = workspacesRoot + "/%s/%s/methodconfigs".format(workspace.namespace, workspace.name)
  private final val updateAttributesPath = workspacesRoot + "/%s/%s/updateAttributes".format(workspace.namespace, workspace.name)
  private final val setAttributesPath = workspacesRoot + "/%s/%s/setAttributes".format(workspace.namespace, workspace.name)
  private final val tsvAttributesImportPath = workspacesRoot + "/%s/%s/importAttributesTSV".format(workspace.namespace, workspace.name)
  private final val tsvAttributesExportPath = workspacesRoot + "/%s/%s/exportAttributesTSV".format(workspace.namespace, workspace.name)
  private final val batchUpsertPath = s"${workspacesRoot}/${workspace.namespace}/${workspace.name}/entities/batchUpsert"
  private final val aclPath = workspacesRoot + "/%s/%s/acl".format(workspace.namespace, workspace.name)
  private final val sendChangeNotificationPath = workspacesRoot + "/%s/%s/sendChangeNotification".format(workspace.namespace, workspace.name)
  private final val accessInstructionsPath = workspacesRoot + "/%s/%s/accessInstructions".format(workspace.namespace, workspace.name)
  private final val clonePath = workspacesRoot + "/%s/%s/clone".format(workspace.namespace, workspace.name)
  private final val lockPath = workspacesRoot + "/%s/%s/lock".format(workspace.namespace, workspace.name)
  private final val unlockPath = workspacesRoot + "/%s/%s/unlock".format(workspace.namespace, workspace.name)
  private final val bucketPath = workspacesRoot + "/%s/%s/checkBucketReadAccess".format(workspace.namespace, workspace.name)
  private final val tsvImportPath = workspacesRoot + "/%s/%s/importEntities".format(workspace.namespace, workspace.name)
  private final val tsvImportFlexiblePath = workspacesRoot + "/%s/%s/flexibleImportEntities".format(workspace.namespace, workspace.name)
  private final val bagitImportPath = workspacesRoot + "/%s/%s/importBagit".format(workspace.namespace, workspace.name)
  private final val pfbImportPath = workspacesRoot + "/%s/%s/importPFB".format(workspace.namespace, workspace.name)
  private final val importJobPath = workspacesRoot + "/%s/%s/importJob".format(workspace.namespace, workspace.name)
  private final val importJobStatusPath = workspacesRoot + "/%s/%s/importJob".format(workspace.namespace, workspace.name)
  private final val bucketUsagePath = s"$workspacesPath/bucketUsage"
  private final val usBucketStorageCostEstimatePath = workspacesRoot + "/%s/%s/storageCostEstimate".format("usBucketWorkspace", workspace.name)
  private final val europeWest1storageCostEstimatePath = workspacesRoot + "/%s/%s/storageCostEstimate".format("europeWest1BucketWorkspace", workspace.name)
  private final val tagAutocompletePath = s"$workspacesRoot/tags"
  private final val executionEngineVersionPath = "/version/executionEngine"

  private def catalogPath(ns:String=workspace.namespace, name:String=workspace.name) =
    workspacesRoot + "/%s/%s/catalog".format(ns, name)

  val localShareLogDao: MockShareLogDAO = new WorkspaceApiServiceSpecShareLogDAO

  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app.copy(shareLogDAO = localShareLogDao))
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService = PermissionReportService.constructor(app)
  val entityServiceConstructor: (ModelSchema) => EntityService = EntityService.constructor(app)

  val nihProtectedAuthDomain = ManagedGroupRef(RawlsGroupName("dbGapAuthorizedUsers"))

  val dummyUserId = "1234"

  val protectedRawlsWorkspace = WorkspaceDetails(
    "attributes",
    "att",
    "id",
    "", //bucketname
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "mb",
    Some(Map()), //attrs
    false,
    Some(Set(nihProtectedAuthDomain)), //authorizationDomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    None,
    Option(DateTime.now()),
    None,
    None,
    WorkspaceState.Ready
  )

  val authDomainRawlsWorkspace = WorkspaceDetails(
    "attributes",
    "att",
    "id",
    "", //bucketname
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "mb",
    Some(Map()), //attrs
    false,
    Some(Set(ManagedGroupRef(RawlsGroupName("secret_realm")))), //authorizationDomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    None,
    Option(DateTime.now()),
    None,
    None,
    WorkspaceState.Ready
  )

  val nonAuthDomainRawlsWorkspace = WorkspaceDetails(
    "attributes",
    "att",
    "id",
    "", //bucketname
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "mb",
    Some(Map()), //attrs
    false,
    Some(Set.empty), //authorizationDomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    None,
    Option(DateTime.now()),
    None,
    None,
    WorkspaceState.Ready
  )

  val protectedRawlsWorkspaceResponse = WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare=Some(false), canCompute=Some(true), catalog=Some(false), protectedRawlsWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None)
  val authDomainRawlsWorkspaceResponse = WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare=Some(false), canCompute=Some(true), catalog=Some(false), authDomainRawlsWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None)
  val nonAuthDomainRawlsWorkspaceResponse = WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare=Some(false), canCompute=Some(true), catalog=Some(false), nonAuthDomainRawlsWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None)

  var rawlsServer: ClientAndServer = _
  var bagitServer: ClientAndServer = _
  var importServiceServer: ClientAndServer = _

  /** Stubs the mock Rawls service to respond to a request. Used for testing passthroughs.
    *
    * @param method HTTP method to respond to
    * @param path   request path
    * @param status status for the response
    */
  def stubRawlsService(method: HttpMethod, path: String, status: StatusCode, body: Option[String] = None, query: Option[(String, String)] = None, requestBody: Option[String] = None): Unit = {
    rawlsServer.reset()
    val request = org.mockserver.model.HttpRequest.request()
      .withMethod(method.name)
      .withPath(path)
    if (query.isDefined) request.withQueryStringParameter(query.get._1, query.get._2)
    requestBody.foreach(request.withBody)
    val response = org.mockserver.model.HttpResponse.response()
      .withHeaders(MockUtils.header).withStatusCode(status.intValue)
    if (body.isDefined) response.withBody(body.get)
    rawlsServer
      .when(request)
      .respond(response)
  }

  /** Stubs the mock Rawls service for creating a new workspace. This represents the expected Rawls API and response
    * behavior for of successful web service request.
    *
    * NOTE: This does NOT contain any orchestration business logic! It only creates the request/response objects and
    * configures the stub Rawls server.
    *
    * @param namespace  namespace for the new workspace
    * @param name       name for the new workspace
    * @param authDomain (optional) authorization domain for the new workspace
    * @return pair of expected WorkspaceRequest and the Workspace that the stub will respond with
    */
  def stubRawlsCreateWorkspace(namespace: String, name: String, authDomain: Set[ManagedGroupRef] = Set.empty): (WorkspaceRequest, WorkspaceDetails) = {
    rawlsServer.reset()
    val rawlsRequest = WorkspaceRequest(namespace, name, Map(), Option(authDomain))
    val rawlsResponse = WorkspaceDetails(namespace, name, "foo", "bar", Some("wf-collection"), DateTime.now(), DateTime.now(), "bob", Some(Map()), false, Some(authDomain), WorkspaceVersions.V2, GoogleProjectId("googleProject"), Some(GoogleProjectNumber("googleProjectNumber")), Some(RawlsBillingAccountName("billingAccount")), None, None, Option(DateTime.now()), None, None, WorkspaceState.Ready)
    stubRawlsService(HttpMethods.POST, workspacesRoot, Created, Option(rawlsResponse.toJson.compactPrint))
    (rawlsRequest, rawlsResponse)
  }

  /** Stubs the mock Rawls service for cloning an existing workspace. This represents the expected Rawls API and
    * response behavior for a successful web service request.
    *
    * NOTE: This does NOT contain any orchestration business logic! It only creates the request/response objects and
    * configures the stub Rawls server.
    *
    * @param namespace  namespace for the new cloned workspace
    * @param name       name for the new cloned workspace
    * @param authDomain (optional) authorization domain for the new cloned workspace
    * @param attributes (optional) attributes expected to be given to rawls for the new cloned workspace
    * @return pair of expected WorkspaceRequest and the Workspace that the stub will respond with
    */
  def stubRawlsCloneWorkspace(namespace: String, name: String, authDomain: Set[ManagedGroupRef] = Set.empty, attributes: Attributable.AttributeMap = Map()): (WorkspaceRequest, WorkspaceDetails) = {
    rawlsServer.reset()
    val published: (AttributeName, AttributeBoolean) = AttributeName("library", "published") -> AttributeBoolean(false)
    val discoverable = AttributeName("library", "discoverableByGroups") -> AttributeValueEmptyList
    val rawlsRequest: WorkspaceRequest = WorkspaceRequest(namespace, name, attributes + published + discoverable, Option(authDomain))
    val rawlsResponse = WorkspaceDetails(namespace, name, "foo", "bar", Some("wf-collection"), DateTime.now(), DateTime.now(), "bob", Some(attributes + published + discoverable), false, Some(authDomain), WorkspaceVersions.V2, GoogleProjectId("googleProject"), Some(GoogleProjectNumber("googleProjectNumber")), Some(RawlsBillingAccountName("billingAccount")), None, None, Option(DateTime.now()), None, None, WorkspaceState.Ready)
    stubRawlsService(HttpMethods.POST, clonePath, Created, Option(rawlsResponse.toJson.compactPrint))
    (rawlsRequest, rawlsResponse)
  }

  def stubRawlsServiceWithError(method: HttpMethod, path: String, status: StatusCode) = {
    rawlsServer.reset()
    rawlsServer
      .when(request().withMethod(method.name).withPath(path))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(status.intValue)
          .withBody(rawlsErrorReport(status).toJson.compactPrint)
      )
  }

  def bagitService() = {
    val bothBytes = IOUtils.toByteArray(getClass.getClassLoader.getResourceAsStream("testfiles/bagit/testbag.zip"))
    val neitherBytes = IOUtils.toByteArray(getClass.getClassLoader.getResourceAsStream("testfiles/bagit/nothingbag.zip"))
    val emptyBytes = IOUtils.toByteArray(getClass.getClassLoader.getResourceAsStream("testfiles/bagit/empty.zip"))
    val notAZipBytes = IOUtils.toByteArray(getClass.getClassLoader.getResourceAsStream("testfiles/bagit/not_a_zip.txt"))

    // url -> byte array for mockserver
    val mappings = Map(
      "/both.zip" -> bothBytes,
      "/neither.zip" -> neitherBytes,
      "/empty.zip" -> emptyBytes,
      "/notazip.zip" -> notAZipBytes,
    )

    // bagit import requires https urls; set up SSL
    HttpsURLConnection.setDefaultSSLSocketFactory(KeyStoreFactory.keyStoreFactory().sslContext().getSocketFactory())

    // set up mockserver for all paths defined above
    mappings.foreach { entry =>
      bagitServer
        .when(request().withMethod("GET").withPath(entry._1))
        .respond(
          org.mockserver.model.HttpResponse.response()
            .withStatusCode(200)
            .withBody(org.mockserver.model.BinaryBody.binary(entry._2)))
    }
  }

  override def beforeAll(): Unit = {
    rawlsServer = startClientAndServer(MockUtils.workspaceServerPort)
    bagitServer = startClientAndServer(MockUtils.bagitServerPort)
    bagitService()
    importServiceServer = startClientAndServer(MockUtils.importServiceServerPort)
  }

  override def afterAll(): Unit = {
    rawlsServer.stop
    bagitServer.stop
    importServiceServer.stop
  }

  override def beforeEach(): Unit = {
    this.searchDao.reset()
  }

  override def afterEach(): Unit = {
    importServiceServer.reset
    this.searchDao.reset()
  }

  //there are many values in the response that in reality cannot be predicted
  //we will only compare the key details: namespace, name, authdomain, attributes
  def assertWorkspaceDetailsEqual(expected: WorkspaceDetails, actual: WorkspaceDetails) = {
    actual.namespace should equal(expected.namespace)
    actual.name should equal(expected.name)
    actual.attributes should equal(expected.attributes)
    actual.authorizationDomain should equal(expected.authorizationDomain)
  }

  "WorkspaceService Passthrough Negative Tests" - {

    "Passthrough tests on the /workspaces path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, POST methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.POST) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/methodconfigs path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/methodconfigs") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
      Seq("this","workspace") foreach { prefix =>
        s"Forbidden error is returned for HTTP POST with an output to $prefix.library:" in {
          val methodConfigs = MethodConfiguration("namespace", "name", Some("root"), None, Map.empty, Map("value" -> AttributeString(s"$prefix.library:param")), MethodRepoMethod("methodnamespace", "methodname", 1))
          Post(methodconfigsPath, methodConfigs) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(Forbidden)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/acl path" - {
      "MethodNotAllowed error is returned for HTTP PUT, POST, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/acl") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/clone path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, GET, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/clone") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/lock path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, GET, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/lock") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/unlock path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, GET, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/unlock") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/checkBucketReadAccess path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, PUT, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/checkBucketReadAccess") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/sendChangeNotification path" - {
      "MethodNotAllowed error is returned for HTTP GET, PATCH, PUT, DELETE methods" in {
        List(HttpMethods.GET, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/sendChangeNotification") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/accessInstructions path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, PUT, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)("/api/workspaces/namespace/name/accessInstructions") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/bucketUsage path" - {
      List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) foreach { method =>
        s"MethodNotAllowed error is returned for $method" in {
          new RequestBuilder(method)("/api/workspaces/namespace/name/bucketUsage") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/tags path" - {
      List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) foreach { method =>
        s"MethodNotAllowed error is returned for $method" in {
          new RequestBuilder(method)("/api/workspaces/tags") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }
  }

  "WorkspaceService Passthrough Tests" - {

    "Passthrough tests on the /workspaces path" - {
      List(HttpMethods.GET) foreach { method =>
        s"OK status is returned for HTTP $method" in {
          val dao = new MockRawlsDAO
          val rwr = dao.rawlsWorkspaceResponseWithAttributes.copy(canShare=Some(false))
          val lrwr = Seq.fill(2){rwr}
          stubRawlsService(method, workspacesRoot, OK, Some(lrwr.toJson.compactPrint))
          new RequestBuilder(method)(workspacesRoot) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          }
        }
      }
    }

    "Passthrough tests on the GET /workspaces/%s/%s path" - {
      s"OK status is returned for HTTP GET (workspace in authdomain)" in {
        stubRawlsService(HttpMethods.GET, workspacesPath, OK, Some(authDomainRawlsWorkspaceResponse.toJson.compactPrint))
        Get(workspacesPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
          //generally this is not how we want to treat the response
          //it should already be returned as JSON but for some strange reason it's being returned as text/plain
          //here we take the plain text and force it to be json so we can get the test to work
          assert(entityAs[String].parseJson.convertTo[UIWorkspaceResponse].workspace.get.authorizationDomain.get.nonEmpty)
        }
      }

      s"OK status is returned for HTTP GET (non-auth-domained workspace)" in {
        stubRawlsService(HttpMethods.GET, workspacesPath, OK, Some(nonAuthDomainRawlsWorkspaceResponse.toJson.compactPrint))
        Get(workspacesPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
          //generally this is not how we want to treat the response
          //it should already be returned as JSON but for some strange reason it's being returned as text/plain
          //here we take the plain text and force it to be json so we can get the test to work
          assert(entityAs[String].parseJson.convertTo[UIWorkspaceResponse].workspace.get.authorizationDomain.get.isEmpty)
        }
      }

      s"Accepted status is returned for HTTP DELETE" in {
        new RequestBuilder(HttpMethods.DELETE)(workspacesPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(Accepted)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/methodconfigs path" - {
      List(HttpMethods.GET) foreach { method =>
        s"OK status is returned for HTTP $method" in {
          stubRawlsService(method, methodconfigsPath, OK)
          new RequestBuilder(method)(methodconfigsPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          }
        }
      }

      "We should pass through query parameters on the GET" in {

        // Orch should dutifully pass along any value we send it
        Seq("allRepos" -> "true", "allRepos" -> "false", "allRepos" -> "banana") foreach { query =>
          stubRawlsService(HttpMethods.GET, methodconfigsPath, OK, None, Some(query))

          Get(Uri(methodconfigsPath).withQuery(Query(query))) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            rawlsServer.verify(request().withPath(methodconfigsPath).withMethod("GET").withQueryStringParameter(query._1, query._2))

            status should equal(OK)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/acl path" - {
      "OK status is returned for HTTP GET" in {
        stubRawlsService(HttpMethods.GET, aclPath, OK)
        Get(aclPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/sendChangeNotification path" - {
      "OK status is returned for POST" in {
        stubRawlsService(HttpMethods.POST, sendChangeNotificationPath, OK)
        Post(sendChangeNotificationPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/accessInstructions path" - {
      "OK status is returned for GET" in {
        stubRawlsService(HttpMethods.GET, accessInstructionsPath, OK)
        Get(accessInstructionsPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/lock path" - {
      "OK status is returned for PUT" in {
        stubRawlsService(HttpMethods.PUT, lockPath, OK)
        Put(lockPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/unlock path" - {
      "OK status is returned for PUT" in {
        stubRawlsService(HttpMethods.PUT, unlockPath, OK)
        Put(unlockPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/checkBucketReadAccess path" - {
      "OK status is returned for GET" in {
        stubRawlsService(HttpMethods.GET, bucketPath, OK)
        Get(bucketPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/bucketUsage path" - {
      "OK status is returned for GET" in {
        stubRawlsService(HttpMethods.GET, bucketUsagePath, OK)
        Get(bucketUsagePath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /version/executionEngine path" - {
        "OK status is returned for GET" in {
          stubRawlsService(HttpMethods.GET, executionEngineVersionPath, OK)
          Get(executionEngineVersionPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          }
        }
      }

    "Passthrough tests on the workspaces/tags path" - {
      "OK status is returned for GET" in {
        val tagJsonString = """{ "tag": "tagtest", "count": 3 }"""
        stubRawlsService(HttpMethods.GET, tagAutocompletePath, OK, Some(tagJsonString), Some("q", "tag"))
        Get("/api/workspaces/tags", ("q", "tag"))
        new RequestBuilder(HttpMethods.GET)("/api/workspaces/tags?q=tag") ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
          rawlsServer.verify(request().withPath(tagAutocompletePath).withMethod("GET").withQueryStringParameter("q", "tag"))
          status should equal(OK)
          responseAs[String] should equal(tagJsonString)
        }
      }
    }
  }

  "Workspace Non-passthrough Tests" - {
    "POST on /workspaces with 'not protected' workspace request sends non-realm WorkspaceRequest to Rawls and passes back the Rawls status and body" in {
      val (rawlsRequest, rawlsResponse) = stubRawlsCreateWorkspace("namespace", "name")

      val orchestrationRequest = WorkspaceRequest("namespace", "name", Map())
      Post(workspacesRoot, orchestrationRequest) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
        rawlsServer.verify(request().withPath(workspacesRoot).withMethod("POST").withBody(rawlsRequest.toJson.compactPrint))
        status should equal(Created)
        responseAs[WorkspaceDetails] should equal(rawlsResponse)
      }
    }

    "POST on /workspaces with 'protected' workspace request sends NIH-realm WorkspaceRequest to Rawls and passes back the Rawls status and body" in {
      val (rawlsRequest, rawlsResponse) = stubRawlsCreateWorkspace("namespace", "name", authDomain = Set(nihProtectedAuthDomain))

      val orchestrationRequest = WorkspaceRequest("namespace", "name", Map(), Option(Set(nihProtectedAuthDomain)))
      Post(workspacesRoot, orchestrationRequest) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
        rawlsServer.verify(request().withPath(workspacesRoot).withMethod("POST").withBody(rawlsRequest.toJson.compactPrint))
        status should equal(Created)
        responseAs[WorkspaceDetails] should equal(rawlsResponse)
      }
    }

    "OK status is returned from PATCH on /workspaces/%s/%s/acl" in {
      Patch(aclPath, List(WorkspaceACLUpdate("dummy@test.org", WorkspaceAccessLevels.NoAccess, Some(false)))) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
      }
    }

    "POST on /workspaces/.../.../clone for 'not protected' workspace sends non-realm WorkspaceRequest to Rawls and passes back the Rawls status and body" in {
      val (_, rawlsResponse) = stubRawlsCloneWorkspace("namespace", "name")

      val orchestrationRequest: WorkspaceRequest = WorkspaceRequest("namespace", "name", Map())
      Post(clonePath, orchestrationRequest) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(Created)
        assertWorkspaceDetailsEqual(rawlsResponse, responseAs[WorkspaceDetails])
      }
    }

    "POST on /workspaces/.../.../clone for 'protected' workspace sends NIH-realm WorkspaceRequest to Rawls and passes back the Rawls status and body" in {
      val (_, rawlsResponse) = stubRawlsCloneWorkspace("namespace", "name", authDomain = Set(nihProtectedAuthDomain))

      val orchestrationRequest: WorkspaceRequest = WorkspaceRequest("namespace", "name", Map(), Option(Set(nihProtectedAuthDomain)))
      Post(clonePath, orchestrationRequest) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(Created)
        assertWorkspaceDetailsEqual(rawlsResponse, responseAs[WorkspaceDetails])
      }
    }

    "When cloning a published workspace, the clone should not be published" in {
      val (_, rawlsResponse) = stubRawlsCloneWorkspace("namespace", "name",
        attributes = Map(AttributeName("library", "published") -> AttributeBoolean(false), AttributeName("library", "discoverableByGroups") -> AttributeValueEmptyList))

      val published = AttributeName("library", "published") -> AttributeBoolean(true)
      val discoverable = AttributeName("library", "discoverableByGroups") -> AttributeValueList(Seq(AttributeString("all_broad_users")))
      val orchestrationRequest = WorkspaceRequest("namespace", "name", Map(published, discoverable))
      Post(clonePath, orchestrationRequest) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(Created)
        assertWorkspaceDetailsEqual(rawlsResponse, responseAs[WorkspaceDetails])
      }
    }

    "Catalog permission tests on /workspaces/.../.../catalog" - {
      "when calling PATCH" - {
        "should be Forbidden as reader" in {
          val content = HttpEntity(ContentTypes.`application/json`, "[ {\"email\": \"user@gmail.com\",\"catalog\": true} ]")
          new RequestBuilder(HttpMethods.PATCH)(catalogPath("reader"), content) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(Forbidden)
          }
        }
        "should be Forbidden as writer" in {
          val content = HttpEntity(ContentTypes.`application/json`, "[ {\"email\": \"user@gmail.com\",\"catalog\": true} ]")
          new RequestBuilder(HttpMethods.PATCH)(catalogPath("unpublishedwriter"), content) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(Forbidden)
          }
        }
        "should be OK as owner" in {
          val content = HttpEntity(ContentTypes.`application/json`, "[ {\"email\": \"user@gmail.com\",\"catalog\": true} ]")
          new RequestBuilder(HttpMethods.PATCH)(catalogPath(), content) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
            val expected = WorkspaceCatalogUpdateResponseList(Seq(WorkspaceCatalogResponse("userid", true)),Seq.empty)
            responseAs[WorkspaceCatalogUpdateResponseList] should equal (expected)

          }
        }
      }
      "when calling GET" - {
        "should be OK as reader" in {
          new RequestBuilder(HttpMethods.GET)(catalogPath("reader")) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          }
        }
        "should be OK as writer" in {
          new RequestBuilder(HttpMethods.GET)(catalogPath("unpublishedwriter")) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          }
        }
      }
    }

    "WorkspaceService TSV Tests" - {

      "when calling any method other than POST on workspaces/*/*/importEntities path" - {
        "should receive a MethodNotAllowed error" in {
          List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(tsvImportPath, MockTSVFormData.membershipValid) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }

      "when calling POST on the workspaces/*/*/importEntities path" - {
        "should 400 Bad Request if the TSV type is missing" in {
          (Post(tsvImportPath, MockTSVFormData.missingTSVType)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if the TSV type is nonsense" in {
          (Post(tsvImportPath, MockTSVFormData.nonexistentTSVType)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if the TSV entity type doesn't end in _id" in {
          (Post(tsvImportPath, MockTSVFormData.malformedEntityType)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "a membership-type TSV" - {
          "should 400 Bad Request if the entity type is unknown" in {
            (Post(tsvImportPath, MockTSVFormData.membershipUnknownFirstColumnHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if the entity type is not a collection type" in {
            (Post(tsvImportPath, MockTSVFormData.membershipNotCollectionType)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if the collection members header is missing" in {
            (Post(tsvImportPath, MockTSVFormData.membershipMissingMembersHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if it contains other headers than its collection members" in {
            (Post(tsvImportPath, MockTSVFormData.membershipExtraAttributes)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 200 OK if it has the correct headers and valid internals" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.membershipValid)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has the correct headers and valid internals followed by multiple newlines" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.membershipValidWithMultipleNewlines)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has the correct headers and valid internals followed by multiple delimiter-only lines" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.membershipValidWithMultipleDelimiterOnlylines)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }
        }

        "an entity-type TSV" - {
          "should 400 Bad Request if the entity type is unknown calling default import" in {
            (Post(tsvImportPath, MockTSVFormData.entityUnknownFirstColumnHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 200 OK if the entity type is unknown and calling flexible import" in {
            (Post(tsvImportFlexiblePath, MockTSVFormData.entityUnknownFirstColumnHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 400 Bad Request if it contains duplicated entities to update" in {
            (Post(tsvImportPath, MockTSVFormData.entityHasDupes)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if it contains collection member headers" in {
            (Post(tsvImportPath, MockTSVFormData.entityHasCollectionMembers)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if it is missing required attribute headers" in {
            (Post(tsvImportPath, MockTSVFormData.entityUpdateMissingRequiredAttrs)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 200 OK if there's no data" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.entityHasNoRows)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has the full set of required attribute headers" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.entityUpdateWithRequiredAttrs)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has valid rows followed by multiple newlines" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.entityUpdateWithMultipleNewlines)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has valid rows followed by multiple delimiter-only lines" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.entityUpdateWithMultipleDelimiterOnlylines)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has the full set of required attribute headers, plus optionals" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.entityUpdateWithRequiredAndOptionalAttrs)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }
        }

        "an update-type TSV" - {
          "should 400 BadRequest if the entity type is non-FC model with calling default import" in {
            (Post(tsvImportPath, MockTSVFormData.updateNonModelFirstColumnHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)            }
          }

          "should 200 OK if the entity type is non-FC model when calling the flexible import" in {
            stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
            (Post(tsvImportFlexiblePath, MockTSVFormData.updateNonModelFirstColumnHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 400 Bad Request if it contains duplicated entities to update" in {
            (Post(tsvImportPath, MockTSVFormData.updateHasDupes)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if it contains collection member headers" in {
            (Post(tsvImportPath, MockTSVFormData.updateHasCollectionMembers)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 200 OK even if it is missing required attribute headers" in {
            stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
            (Post(tsvImportPath, MockTSVFormData.updateMissingRequiredAttrs)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has the full set of required attribute headers" in {
            stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
            (Post(tsvImportPath, MockTSVFormData.updateWithRequiredAttrs)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has the full set of required attribute headers, plus optionals" in {
            stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
            (Post(tsvImportPath, MockTSVFormData.updateWithRequiredAndOptionalAttrs)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }
        }

        "a default-type TSV" - {
          "that follows the same format as an entity-type TSV" - {
            "should 200 OK if there's no data" in {
              stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
              (Post(tsvImportPath, MockTSVFormData.defaultHasNoRows)
                ~> dummyUserIdHeaders(dummyUserId)
                ~> sealRoute(workspaceRoutes)) ~> check {
                status should equal(OK)
              }
            }

            "should 200 OK if it has the full set of required attribute headers" in {
              stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
              (Post(tsvImportPath, MockTSVFormData.defaultUpdateWithRequiredAttrs)
                ~> dummyUserIdHeaders(dummyUserId)
                ~> sealRoute(workspaceRoutes)) ~> check {
                status should equal(OK)
              }
            }

            "should 200 OK if it has the full set of required attribute headers, plus optionals" in {
              stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
              (Post(tsvImportPath, MockTSVFormData.defaultUpdateWithRequiredAndOptionalAttrs)
                ~> dummyUserIdHeaders(dummyUserId)
                ~> sealRoute(workspaceRoutes)) ~> check {
                status should equal(OK)
              }
            }
          }

          "that follows the same format as a membership-type TSV" - {
            "should 400 Bad Request even if it has the correct headers and valid internals" in {
              stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
              (Post(tsvImportPath, MockTSVFormData.defaultMembershipValid)
                ~> dummyUserIdHeaders(dummyUserId)
                ~> sealRoute(workspaceRoutes)) ~> check {
                status should equal(BadRequest)
                errorReportCheck("FireCloud", BadRequest)
              }
            }
          }
        }
      }
    }

    "WorkspaceService BagIt Tests" - {
      "should unbundle a bagit containing both participants and samples" in {
        stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpsert", NoContent)
        (Post(bagitImportPath, HttpEntity(MediaTypes.`application/json`, s"""{"bagitURL":"https://localhost:$bagitServerPort/both.zip", "format":"TSV" }"""))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(OK)
        }
      }

      "should 400 if a bagit doesn't have either participants or samples" in {
        stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpsert", NoContent)
        (Post(bagitImportPath, HttpEntity(MediaTypes.`application/json`, s"""{"bagitURL":"https://localhost:$bagitServerPort/neither.zip", "format":"TSV" }"""))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(BadRequest)
        }
      }

      "should 400 if a bagit request has an invalid format" in {
        stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpsert", NoContent)
        (Post(bagitImportPath, HttpEntity(MediaTypes.`application/json`, s"""{"bagitURL":"https://localhost:$bagitServerPort/both.zip", "format":"garbage" }"""))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(BadRequest)
        }
      }

      "should 400 if a bagit is empty" in {
        stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpsert", NoContent)
        (Post(bagitImportPath, HttpEntity(MediaTypes.`application/json`, s"""{"bagitURL":"https://localhost:$bagitServerPort/empty.zip", "format":"TSV" }"""))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(BadRequest)
          val errRpt = responseAs[ErrorReport]
          errRpt.message shouldBe s"BDBag has no entries."
        }
      }

      "should 400 if a bagit is unzippable" in {
        stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpsert", NoContent)
        (Post(bagitImportPath, HttpEntity(MediaTypes.`application/json`, s"""{"bagitURL":"https://localhost:$bagitServerPort/notazip.zip", "format":"TSV" }"""))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(BadRequest)
          val errRpt = responseAs[ErrorReport]
          errRpt.message shouldBe s"Problem with BDBag: zip END header not found"
        }
      }
    }

    "WorkspaceService importPFB Tests" - {

      "should 400 if import service indicates a bad request" in {

        (Post(pfbImportPath, PFBImportRequest("https://bad.request.avro"))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            responseAs[String] should include ("Bad request as reported by import service")
          }
      }

      "should 403 if import service access is forbidden" in {
        (Post(pfbImportPath, PFBImportRequest("https://forbidden.avro"))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(Forbidden)
            responseAs[String] should include ("Missing Authorization: Bearer token in header")
          }
      }

      "should propagate any other errors from import service" in {
        // we use UnavailableForLegalReasons as a proxy for "some error we didn't expect"
        (Post(pfbImportPath, PFBImportRequest("https://its.lawsuit.time.avro"))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(UnavailableForLegalReasons)
            responseAs[String] should include ("import service message")
        }
      }

      "should 202 (Accepted) if everything validated and import request was accepted" in {

        val pfbPath = "https://good.avro"

        val orchExpectedPayload = AsyncImportResponse(url = pfbPath,
                                                   jobId = "MockImportServiceDAO will generate a random UUID",
                                                   workspace = WorkspaceName(workspace.namespace, workspace.name))

        (Post(pfbImportPath, PFBImportRequest("https://good.avro"))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(Accepted)
            val jobResponse = responseAs[AsyncImportResponse]
            jobResponse.url should be (orchExpectedPayload.url)
            jobResponse.workspace should be (orchExpectedPayload.workspace)
            jobResponse.jobId  should not be empty
          }
      }

    }

    "WorkspaceService importPFB job-status Tests" - {

      List(importJobStatusPath, pfbImportPath) foreach { pathUnderTest =>
        s"Successful passthrough should return OK with payload for $pathUnderTest" in {

          val jobId = UUID.randomUUID().toString

          val responsePayload = JsObject(
            ("id", JsString(jobId)),
            ("status", JsString("Running"))
          )

          importServiceServer
            .when(request()
              .withMethod("GET")
              .withPath(s"/${workspace.namespace}/${workspace.name}/imports/$jobId"))
            .respond(org.mockserver.model.HttpResponse.response()
              .withStatusCode(OK.intValue)
              .withBody(responsePayload.compactPrint)
              .withHeader("Content-Type", "application/json"))

          (Get(s"$pathUnderTest/$jobId")
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            responseAs[String].parseJson should be (responsePayload) // to address string-formatting issues
          }
        }

        s"Passthrough should not pass unrecognized HTTP verbs for $pathUnderTest" in {
          (Delete(s"$pathUnderTest/dummyJobId")
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "WorkspaceService importPFB list-jobs tests" - {

      List(pfbImportPath, importJobPath) foreach { pathUnderTest =>

        s"for path $pathUnderTest" - {

          "Successful passthrough should return OK with payload" in {
            val responsePayload = JsArray(
              JsObject(
                ("id", JsString(UUID.randomUUID().toString)),
                ("status", JsString("Running"))
              ),
              JsObject(
                ("id", JsString(UUID.randomUUID().toString)),
                ("status", JsString("Error"))
              ),
              JsObject(
                ("id", JsString(UUID.randomUUID().toString)),
                ("status", JsString("ImAUnitTest"))
              )
            )

            importServiceServer
              .when(request()
                .withMethod("GET")
                .withPath(s"/${workspace.namespace}/${workspace.name}/imports"))
              .respond(org.mockserver.model.HttpResponse.response()
                .withStatusCode(OK.intValue)
                .withBody(responsePayload.compactPrint)
                .withHeader("Content-Type", "application/json"))

            (Get(pathUnderTest)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
              responseAs[String].parseJson should be (responsePayload) // to address string-formatting issues
            }
          }

          "Passthrough should pass on querystrings" in {
            val responsePayload = JsArray(
              JsObject(
                ("id", JsString(UUID.randomUUID().toString)),
                ("status", JsString("Running"))
              )
            )

            val k1 = UUID.randomUUID().toString
            val v1 = UUID.randomUUID().toString
            val k2 = UUID.randomUUID().toString
            val v2 = UUID.randomUUID().toString

            val queryString = s"$k1=$v1&$k2=$v2"

            importServiceServer
              .when(request()
                .withMethod("GET")
                .withPath(s"/${workspace.namespace}/${workspace.name}/imports")
                .withQueryStringParameters(new Parameter(k1, v1), new Parameter(k2, v2)))
              .respond(org.mockserver.model.HttpResponse.response()
                .withStatusCode(OK.intValue)
                .withBody(responsePayload.compactPrint)
                .withHeader("Content-Type", "application/json"))

            (Get(s"$pathUnderTest?$queryString")
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
              responseAs[String].parseJson should be (responsePayload) // to address string-formatting issues
            }
          }

          "Passthrough should not pass unrecognized HTTP verbs" in {
            (Delete(s"$pathUnderTest")
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(MethodNotAllowed)
            }
          }
        }
      }

    }

    "WorkspaceService POST importJob Tests" - {

      List(FILETYPE_PFB, FILETYPE_TDR) foreach { filetype =>

        s"for filetype $filetype" - {

          "should 400 if import service indicates a bad request" in {

            (Post(importJobPath, AsyncImportRequest("https://bad.request.avro", filetype))
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              responseAs[String] should include ("Bad request as reported by import service")
            }
          }

          "should 403 if import service access is forbidden" in {
            (Post(importJobPath, AsyncImportRequest("https://forbidden.avro", filetype))
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(Forbidden)
              responseAs[String] should include ("Missing Authorization: Bearer token in header")
            }
          }

          "should propagate any other errors from import service" in {
            // we use UnavailableForLegalReasons as a proxy for "some error we didn't expect"
            (Post(importJobPath, AsyncImportRequest("https://its.lawsuit.time.avro", filetype))
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(UnavailableForLegalReasons)
              responseAs[String] should include ("import service message")
            }
          }

          "should 202 (Accepted) if everything validated and import request was accepted" in {

            val pfbPath = "https://good.avro"

            val orchExpectedPayload = AsyncImportResponse(url = pfbPath,
              jobId = "MockImportServiceDAO will generate a random UUID",
              workspace = WorkspaceName(workspace.namespace, workspace.name))

            (Post(importJobPath, AsyncImportRequest("https://good.avro", filetype))
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(Accepted)
              val jobResponse = responseAs[AsyncImportResponse]
              jobResponse.url should be (orchExpectedPayload.url)
              jobResponse.workspace should be (orchExpectedPayload.workspace)
              jobResponse.jobId  should not be empty
            }
          }
        }
      }

    }

    "Workspace updateAttributes tests" - {
      "when calling any method other than PATCH on workspaces/*/*/updateAttributes path" - {
        "should receive a MethodNotAllowed error" in {
          List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.GET, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(updateAttributesPath, HttpEntity(MediaTypes.`application/json`, "{}")) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }

      "when calling PATCH on workspaces/*/*/updateAttributes path" - {
        "should 400 Bad Request if the payload is malformed" in {
          (Patch(updateAttributesPath, HttpEntity(MediaTypes.`application/json`, "{{{"))
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 200 OK if the payload is ok" in {
          (Patch(updateAttributesPath,
            HttpEntity(MediaTypes.`application/json`, """[
                                                        |  {
                                                        |    "op": "AddUpdateAttribute",
                                                        |    "attributeName": "library:dataCategory",
                                                        |    "addUpdateAttribute": "test-attribute-value"
                                                        |  }
                                                        |]""".stripMargin))
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(!this.searchDao.indexDocumentInvoked.get(), "Should not be indexing an unpublished WS")
          }
        }

        "should republish if the document is already published" in {

          (Patch(workspacesRoot + "/%s/%s/updateAttributes".format(WorkspaceApiServiceSpec.publishedWorkspace.namespace, WorkspaceApiServiceSpec.publishedWorkspace.name),
            HttpEntity(MediaTypes.`application/json`, """[
                                                        |  {
                                                        |    "op": "AddUpdateAttribute",
                                                        |    "attributeName": "library:dataCategory",
                                                        |    "addUpdateAttribute": "test-attribute-value"
                                                        |  }
                                                        |]""".stripMargin))
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(this.searchDao.indexDocumentInvoked.get(), "Should have republished this published WS when changing attributes")
          }
        }

      }
    }

    "Workspace setAttributes tests" - {
      "when calling any method other than PATCH on workspaces/*/*/setAttributes path" - {
        "should receive a MethodNotAllowed error" in {
          List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.GET, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(setAttributesPath, HttpEntity(MediaTypes.`application/json`, "{}")) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }

      "when calling PATCH on workspaces/*/*/setAttributes path" - {
        "should 400 Bad Request if the payload is malformed" in {
          (Patch(setAttributesPath, HttpEntity(MediaTypes.`application/json`, "{{{"))
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 200 OK if the payload is ok" in {
          (Patch(setAttributesPath,
            HttpEntity(MediaTypes.`application/json`, """{"description": "something",
                                                        | "array": [1, 2, 3]
                                                        | }""".stripMargin))
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(!this.searchDao.indexDocumentInvoked.get(), "Should not be indexing an unpublished WS")
          }
        }

        "should republish if the document is already published" in {

          (Patch(workspacesRoot + "/%s/%s/setAttributes".format(WorkspaceApiServiceSpec.publishedWorkspace.namespace, WorkspaceApiServiceSpec.publishedWorkspace.name),
            HttpEntity(MediaTypes.`application/json`, """{"description": "something",
                                                        | "array": [1, 2, 3]
                                                        | }""".stripMargin))
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(this.searchDao.indexDocumentInvoked.get(), "Should have republished this published WS when changing attributes")
          }
        }

      }

      "when calling POST on the workspaces/*/*/importAttributesTSV path" - {
        "should 200 OK if it has the correct headers and valid internals" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.addNewWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          })
        }

        "should 400 Bad Request if first row does not start with \"workspace\"" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.wrongHeaderWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(BadRequest)
          })
        }

        "should 400 Bad Request if there are more names than values" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.tooManyNamesWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(BadRequest)
          })
        }

        "should 400 Bad Request if there are more values than names" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.tooManyValuesWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(BadRequest)
          })
        }

        "should 400 Bad Request if there are more than 2 rows" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.tooManyRowsWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(BadRequest)
          })
        }

        "should 400 Bad Request if there are fewer than 2 rows" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.tooFewRowsWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(BadRequest)
          })
        }

      }
    }

    "Workspace storage cost estimate tests" - {
      "when calling any method other than GET on workspaces/*/*/storageCostEstimate" - {
        "should return 405 Method Not Allowed for anything other than GET" in {
          List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.PATCH, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(usBucketStorageCostEstimatePath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
                status should be (MethodNotAllowed)
              }
          }
        }
      }

      "when calling GET on workspaces/*/*/storageCostEstimate" - {
        "should return 200 with result for us region" in {
          Get(usBucketStorageCostEstimatePath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should be (OK)
            // 256000000000 / (1024 * 1024 * 1024) *0.01
            responseAs[WorkspaceStorageCostEstimate].estimate should be ("$2.38")
          }
        }
      }

      "when calling GET on workspaces/*/*/storageCostEstimate" - {
        "should return 200 with result for different europe east 1 region." in {
          Get(europeWest1storageCostEstimatePath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
            status should be (OK)
            // 256000000000 / (1024 * 1024 * 1024) *0.02
            responseAs[WorkspaceStorageCostEstimate].estimate should be ("$4.77")
          }
        }
      }
    }
  }
}
