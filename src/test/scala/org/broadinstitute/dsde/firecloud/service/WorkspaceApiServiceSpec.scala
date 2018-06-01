package org.broadinstitute.dsde.firecloud.service

import org.apache.commons.io.IOUtils
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.MockRawlsDAO
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.mock.{MockTSVFormData, MockUtils}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.webservice.WorkspaceApiService
import org.broadinstitute.dsde.rawls.model._
import org.joda.time.DateTime
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.scalatest.BeforeAndAfterEach
import spray.http._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._

object WorkspaceApiServiceSpec {

  val publishedWorkspace = Workspace(
    "namespace",
    "name-published",
    Set.empty,
    "workspace_id",
    "buckety_bucket",
    DateTime.now(),
    DateTime.now(),
    "my_workspace_creator",
    Map(AttributeName("library", "published") -> AttributeBoolean(true)), //attributes
    Map(), //acls
    Map(), //authdomain acls
    false //locked
  )

}

class WorkspaceApiServiceSpec extends BaseServiceSpec with WorkspaceApiService with BeforeAndAfterEach {

  def actorRefFactory = system

  val workspace = Workspace(
    "namespace",
    "name",
    Set.empty,
    "workspace_id",
    "buckety_bucket",
    DateTime.now(),
    DateTime.now(),
    "my_workspace_creator",
    Map(), //attributes
    Map(), //acls
    Map(), //authdomain acls
    false //locked
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
  private final val bagitImportPath = workspacesRoot + "/%s/%s/importBagit".format(workspace.namespace, workspace.name)
  private final val bucketUsagePath = s"$workspacesPath/bucketUsage"
  private final val storageCostEstimatePath = s"$workspacesPath/storageCostEstimate"
  private final val tagAutocompletePath = s"$workspacesRoot/tags"
  private final val executionEngineVersionPath = "/version/executionEngine"
  private final val genomicsOperationsPath = s"$workspacesPath/genomics/operations/$jobId"

  private def catalogPath(ns:String=workspace.namespace, name:String=workspace.name) =
    workspacesRoot + "/%s/%s/catalog".format(ns, name)

  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app)
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService = PermissionReportService.constructor(app)

  val nihProtectedAuthDomain = ManagedGroupRef(RawlsGroupName("dbGapAuthorizedUsers"))

  val protectedRawlsWorkspace = Workspace(
    "attributes",
    "att",
    Set(nihProtectedAuthDomain), //authdomain
    "id",
    "", //bucketname
    DateTime.now(),
    DateTime.now(),
    "mb",
    Map(), //attrs
    Map(), //acls
    Map(), //authdomain acls
    false
  )

  val authDomainRawlsWorkspace = Workspace(
    "attributes",
    "att",
    Set(ManagedGroupRef(RawlsGroupName("secret_realm"))), //authdomain
    "id",
    "", //bucketname
    DateTime.now(),
    DateTime.now(),
    "mb",
    Map(), //attrs
    Map(), //acls
    Map(), //authdomain acls
    false
  )

  val nonAuthDomainRawlsWorkspace = Workspace(
    "attributes",
    "att",
    Set.empty, //authdomain
    "id",
    "", //bucketname
    DateTime.now(),
    DateTime.now(),
    "mb",
    Map(), //attrs
    Map(), //acls
    Map(), //authdomain acls
    false
  )

  val protectedRawlsWorkspaceResponse = WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare=false, canCompute=true, catalog=false, protectedRawlsWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)
  val authDomainRawlsWorkspaceResponse = WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare=false, canCompute=true, catalog=false, authDomainRawlsWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)
  val nonAuthDomainRawlsWorkspaceResponse = WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare=false, canCompute=true, catalog=false, nonAuthDomainRawlsWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)

  var rawlsServer: ClientAndServer = _
  var bagitServer: ClientAndServer = _

  /** Stubs the mock Rawls service to respond to a request. Used for testing passthroughs.
    *
    * @param method HTTP method to respond to
    * @param path   request path
    * @param status status for the response
    */
  def stubRawlsService(method: HttpMethod, path: String, status: StatusCode, body: Option[String] = None, query: Option[(String, String)] = None): Unit = {
    rawlsServer.reset()
    val request = org.mockserver.model.HttpRequest.request()
      .withMethod(method.name)
      .withPath(path)
    if (query.isDefined) request.withQueryStringParameter(query.get._1, query.get._2)
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
  def stubRawlsCreateWorkspace(namespace: String, name: String, authDomain: Set[ManagedGroupRef] = Set.empty): (WorkspaceRequest, Workspace) = {
    rawlsServer.reset()
    val rawlsRequest = WorkspaceRequest(namespace, name, Map(), Option(authDomain))
    val rawlsResponse = Workspace(namespace, name, authDomain, "foo", "bar", DateTime.now(), DateTime.now(), "bob", Map(), Map(), Map())
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
  def stubRawlsCloneWorkspace(namespace: String, name: String, authDomain: Set[ManagedGroupRef] = Set.empty, attributes: Attributable.AttributeMap = Map()): (WorkspaceRequest, Workspace) = {
    rawlsServer.reset()
    val published: (AttributeName, AttributeBoolean) = AttributeName("library", "published") -> AttributeBoolean(false)
    val discoverable = AttributeName("library", "discoverableByGroups") -> AttributeValueEmptyList
    val rawlsRequest: WorkspaceRequest = WorkspaceRequest(namespace, name, attributes + published + discoverable, Option(authDomain))
    val rawlsResponse = Workspace(namespace, name, authDomain, "foo", "bar", DateTime.now(), DateTime.now(), "bob", attributes, Map(), Map())
    stubRawlsService(HttpMethods.POST, clonePath, Created, Option(rawlsResponse.toJson.compactPrint))
    (rawlsRequest, rawlsResponse)
  }

  def stubRawlsServiceWithError(method: HttpMethod, path: String, status: StatusCode) = {
    rawlsServer.reset()
    rawlsServer
      .when(request().withMethod(method.name).withPath(path).withHeader(authHeader))
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
    println("WE'RE HERE")
    bagitServer
      .when(request().withMethod("GET").withPath("/both.zip"))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withStatusCode(200)
          .withBody(org.mockserver.model.BinaryBody.binary(bothBytes)))

    bagitServer
      .when(request().withMethod("GET").withPath("/neither.zip"))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withStatusCode(200)
          .withBody(org.mockserver.model.BinaryBody.binary(neitherBytes)))
  }

  override def beforeAll(): Unit = {
    rawlsServer = startClientAndServer(MockUtils.workspaceServerPort)
    bagitServer = startClientAndServer(MockUtils.bagitServerPort)
  }

  override def afterAll(): Unit = {
    rawlsServer.stop
    bagitServer.stop
  }

  override def beforeEach(): Unit = {
    this.searchDao.reset
  }

  override def afterEach(): Unit = {
    this.searchDao.reset
  }

  "WorkspaceService Passthrough Negative Tests" - {

    "Passthrough tests on the /workspaces path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, POST methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.POST) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/methodconfigs path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/methodconfigs") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
      Seq("this","workspace") foreach { prefix =>
        s"Forbidden error is returned for HTTP POST with an output to $prefix.library:" in {
          val methodConfigs = MethodConfiguration("namespace", "name", Some("root"), Map.empty, Map.empty, Map("value" -> AttributeString(s"$prefix.library:param")), MethodRepoMethod("methodnamespace", "methodname", 1))
          Post(methodconfigsPath, methodConfigs) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(Forbidden)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/acl path" - {
      "MethodNotAllowed error is returned for HTTP PUT, POST, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/acl") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/clone path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, GET, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/clone") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/lock path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, GET, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/lock") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/unlock path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, GET, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/unlock") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/checkBucketReadAccess path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, PUT, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/checkBucketReadAccess") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/sendChangeNotification path" - {
      "MethodNotAllowed error is returned for HTTP GET, PATCH, PUT, DELETE methods" in {
        List(HttpMethods.GET, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) map {
          method =>
          new RequestBuilder(method)("/api/workspaces/namespace/name/sendChangeNotification") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/accessInstructions path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, PUT, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)("/api/workspaces/namespace/name/accessInstructions") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/bucketUsage path" - {
      List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) foreach { method =>
        s"MethodNotAllowed error is returned for $method" in {
          new RequestBuilder(method)("/api/workspaces/namespace/name/bucketUsage") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/tags path" - {
      List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) foreach { method =>
        s"MethodNotAllowed error is returned for $method" in {
          new RequestBuilder(method)("/api/workspaces/tags") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/genomics/operations/%s path" - {
      List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) foreach { method =>
        s"MethodNotAllowed error is returned for $method" in {
          new RequestBuilder(method)(genomicsOperationsPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
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
          val rwr = dao.rawlsWorkspaceResponseWithAttributes.copy(canShare=false)
          val lrwr = Seq.fill(2){rwr}
          stubRawlsService(method, workspacesRoot, OK, Some(lrwr.toJson.compactPrint))
          new RequestBuilder(method)(workspacesRoot) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          }
        }
      }
    }

    "Passthrough tests on the GET /workspaces/%s/%s path" - {
      s"OK status is returned for HTTP GET (workspace in authdomain)" in {
        stubRawlsService(HttpMethods.GET, workspacesPath, OK, Some(authDomainRawlsWorkspaceResponse.toJson.compactPrint))
        Get(workspacesPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
          //generally this is not how we want to treat the response
          //it should already be returned as JSON but for some strange reason it's being returned as text/plain
          //here we take the plain text and force it to be json so we can get the test to work
          assert(entity.asString.parseJson.convertTo[UIWorkspaceResponse].workspace.get.authorizationDomain.nonEmpty)
        }
      }

      s"OK status is returned for HTTP GET (non-realmed workspace)" in {
        stubRawlsService(HttpMethods.GET, workspacesPath, OK, Some(nonAuthDomainRawlsWorkspaceResponse.toJson.compactPrint))
        Get(workspacesPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
          //generally this is not how we want to treat the response
          //it should already be returned as JSON but for some strange reason it's being returned as text/plain
          //here we take the plain text and force it to be json so we can get the test to work
          assert(entity.asString.parseJson.convertTo[UIWorkspaceResponse].workspace.get.authorizationDomain.isEmpty)
        }
      }

      s"OK status is returned for HTTP DELETE" in {
        stubRawlsService(HttpMethods.DELETE, workspacesPath, OK)
        new RequestBuilder(HttpMethods.DELETE)(workspacesPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/methodconfigs path" - {
      List(HttpMethods.GET) foreach { method =>
        s"OK status is returned for HTTP $method" in {
          stubRawlsService(method, methodconfigsPath, OK)
          new RequestBuilder(method)(methodconfigsPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          }
        }
      }

      "We should pass through query parameters on the GET" in {

        // Orch should dutifully pass along any value we send it
        Seq("allRepos" -> "true", "allRepos" -> "false", "allRepos" -> "banana") foreach { query =>
          stubRawlsService(HttpMethods.GET, methodconfigsPath, OK, None, Some(query))

          Get(Uri(methodconfigsPath).withQuery(query)) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            rawlsServer.verify(request().withPath(methodconfigsPath).withMethod("GET").withQueryStringParameter(query._1, query._2))

            status should equal(OK)
          }
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/acl path" - {
      "OK status is returned for HTTP GET" in {
        stubRawlsService(HttpMethods.GET, aclPath, OK)
        Get(aclPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/sendChangeNotification path" - {
      "OK status is returned for POST" in {
        stubRawlsService(HttpMethods.POST, sendChangeNotificationPath, OK)
        Post(sendChangeNotificationPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/accessInstructions path" - {
      "OK status is returned for GET" in {
        stubRawlsService(HttpMethods.GET, accessInstructionsPath, OK)
        Get(accessInstructionsPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/lock path" - {
      "OK status is returned for PUT" in {
        stubRawlsService(HttpMethods.PUT, lockPath, OK)
        Put(lockPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/unlock path" - {
      "OK status is returned for PUT" in {
        stubRawlsService(HttpMethods.PUT, unlockPath, OK)
        Put(unlockPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/checkBucketReadAccess path" - {
      "OK status is returned for GET" in {
        stubRawlsService(HttpMethods.GET, bucketPath, OK)
        Get(bucketPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/bucketUsage path" - {
      "OK status is returned for GET" in {
        stubRawlsService(HttpMethods.GET, bucketUsagePath, OK)
        Get(bucketUsagePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "Passthrough tests on the /version/executionEngine path" - {
        "OK status is returned for GET" in {
          stubRawlsService(HttpMethods.GET, executionEngineVersionPath, OK)
          Get(executionEngineVersionPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          }
        }
      }

    "Passthrough tests on the workspaces/tags path" - {
      "OK status is returned for GET" in {
        val tagJsonString = """{ "tag": "tagtest", "count": 3 }"""
        stubRawlsService(HttpMethods.GET, tagAutocompletePath, OK, Some(tagJsonString), Some("q", "tag"))
        Get("/api/workspaces/tags", ("q", "tag"))
        new RequestBuilder(HttpMethods.GET)("/api/workspaces/tags?q=tag") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          rawlsServer.verify(request().withPath(tagAutocompletePath).withMethod("GET").withQueryStringParameter("q", "tag"))
          status should equal(OK)
          responseAs[String] should equal(tagJsonString)
        }
      }
    }

    "Passthrough tests on the /workspaces/%s/%s/genomics/operations/%s path" - {
      "OK status is returned for GET" in {
        stubRawlsService(HttpMethods.GET, genomicsOperationsPath, OK)
        Get(genomicsOperationsPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should equal (OK)
        }
      }
    }
  }

  "Workspace Non-passthrough Tests" - {
    "POST on /workspaces with 'not protected' workspace request sends non-realm WorkspaceRequest to Rawls and passes back the Rawls status and body" in {
      val (rawlsRequest, rawlsResponse) = stubRawlsCreateWorkspace("namespace", "name")

      val orchestrationRequest = WorkspaceRequest("namespace", "name", Map())
      Post(workspacesRoot, orchestrationRequest) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        rawlsServer.verify(request().withPath(workspacesRoot).withMethod("POST").withBody(rawlsRequest.toJson.prettyPrint))
        status should equal(Created)
        responseAs[Workspace] should equal(rawlsResponse)
      }
    }

    "POST on /workspaces with 'protected' workspace request sends NIH-realm WorkspaceRequest to Rawls and passes back the Rawls status and body" in {
      val (rawlsRequest, rawlsResponse) = stubRawlsCreateWorkspace("namespace", "name", authDomain = Set(nihProtectedAuthDomain))

      val orchestrationRequest = WorkspaceRequest("namespace", "name", Map(), Option(Set(nihProtectedAuthDomain)))
      Post(workspacesRoot, orchestrationRequest) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        rawlsServer.verify(request().withPath(workspacesRoot).withMethod("POST").withBody(rawlsRequest.toJson.prettyPrint))
        status should equal(Created)
        responseAs[Workspace] should equal(rawlsResponse)
      }
    }

    "OK status is returned from PATCH on /workspaces/%s/%s/acl" in {
      Patch(aclPath, List(WorkspaceACLUpdate("dummy@test.org", WorkspaceAccessLevels.NoAccess, Some(false)))) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
      }
    }

    "POST on /workspaces/.../.../clone for 'not protected' workspace sends non-realm WorkspaceRequest to Rawls and passes back the Rawls status and body" in {
      val (rawlsRequest, rawlsResponse) = stubRawlsCloneWorkspace("namespace", "name")

      val orchestrationRequest: WorkspaceRequest = WorkspaceRequest("namespace", "name", Map())
      Post(clonePath, orchestrationRequest) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        rawlsServer.verify(request().withPath(clonePath).withMethod("POST").withBody(rawlsRequest.toJson.prettyPrint))
        status should equal(Created)
        responseAs[Workspace] should equal(rawlsResponse)
      }
    }

    "POST on /workspaces/.../.../clone for 'protected' workspace sends NIH-realm WorkspaceRequest to Rawls and passes back the Rawls status and body" in {
      val (rawlsRequest, rawlsResponse) = stubRawlsCloneWorkspace("namespace", "name", authDomain = Set(nihProtectedAuthDomain))

      val orchestrationRequest: WorkspaceRequest = WorkspaceRequest("namespace", "name", Map(), Option(Set(nihProtectedAuthDomain)))
      Post(clonePath, orchestrationRequest) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        rawlsServer.verify(request().withPath(clonePath).withMethod("POST").withBody(rawlsRequest.toJson.prettyPrint))
        status should equal(Created)
        responseAs[Workspace] should equal(rawlsResponse)
      }
    }

    "When cloning a published workspace, the clone should not be published" in {
      val (rawlsRequest, rawlsResponse) = stubRawlsCloneWorkspace("namespace", "name",
        attributes = Map(AttributeName("library", "published") -> AttributeBoolean(false), AttributeName("library", "discoverableByGroups") -> AttributeValueEmptyList))

      val published = AttributeName("library", "published") -> AttributeBoolean(true)
      val discoverable = AttributeName("library", "discoverableByGroups") -> AttributeValueList(Seq(AttributeString("all_broad_users")))
      val orchestrationRequest = WorkspaceRequest("namespace", "name", Map(published, discoverable))
      Post(clonePath, orchestrationRequest) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        rawlsServer.verify(request().withPath(clonePath).withMethod("POST").withBody(rawlsRequest.toJson.prettyPrint))
        status should equal(Created)
        responseAs[Workspace] should equal(rawlsResponse)
      }
    }

    "Catalog permission tests on /workspaces/.../.../catalog" - {
      "when calling PATCH" - {
        "should be Forbidden as reader" in {
          val content = HttpEntity(ContentTypes.`application/json`, "[ {\"email\": \"user@gmail.com\",\"catalog\": true} ]")
          new RequestBuilder(HttpMethods.PATCH)(catalogPath("reader"), content) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(Forbidden)
          }
        }
        "should be Forbidden as writer" in {
          val content = HttpEntity(ContentTypes.`application/json`, "[ {\"email\": \"user@gmail.com\",\"catalog\": true} ]")
          new RequestBuilder(HttpMethods.PATCH)(catalogPath("unpublishedwriter"), content) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(Forbidden)
          }
        }
        "should be OK as owner" in {
          val content = HttpEntity(ContentTypes.`application/json`, "[ {\"email\": \"user@gmail.com\",\"catalog\": true} ]")
          new RequestBuilder(HttpMethods.PATCH)(catalogPath(), content) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
            val expected = WorkspaceCatalogUpdateResponseList(Seq(WorkspaceCatalogResponse("userid", true)),Seq.empty)
            responseAs[WorkspaceCatalogUpdateResponseList] should equal (expected)

          }
        }
      }
      "when calling GET" - {
        "should be OK as reader" in {
          new RequestBuilder(HttpMethods.GET)(catalogPath("reader")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          }
        }
        "should be OK as writer" in {
          new RequestBuilder(HttpMethods.GET)(catalogPath("unpublishedwriter")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
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
              new RequestBuilder(method)(tsvImportPath, MockTSVFormData.membershipValid) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }

      "when calling POST on the workspaces/*/*/importEntities path" - {
        "should 400 Bad Request if the TSV type is missing" in {
          (Post(tsvImportPath, MockTSVFormData.missingTSVType)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if the TSV type is nonsense" in {
          (Post(tsvImportPath, MockTSVFormData.nonexistentTSVType)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if the TSV entity type doesn't end in _id" in {
          (Post(tsvImportPath, MockTSVFormData.malformedEntityType)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "a membership-type TSV" - {
          "should 400 Bad Request if the entity type is unknown" in {
            (Post(tsvImportPath, MockTSVFormData.membershipUnknownFirstColumnHeader)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if the entity type is not a collection type" in {
            (Post(tsvImportPath, MockTSVFormData.membershipNotCollectionType)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if the collection members header is missing" in {
            (Post(tsvImportPath, MockTSVFormData.membershipMissingMembersHeader)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if it contains other headers than its collection members" in {
            (Post(tsvImportPath, MockTSVFormData.membershipExtraAttributes)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 200 OK if it has the correct headers and valid internals" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.membershipValid)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }
        }

        "an entity-type TSV" - {
          "should 400 Bad Request if the entity type is unknown" in {
            (Post(tsvImportPath, MockTSVFormData.entityUnknownFirstColumnHeader)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if it contains duplicated entities to update" in {
            (Post(tsvImportPath, MockTSVFormData.entityHasDupes)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if it contains collection member headers" in {
            (Post(tsvImportPath, MockTSVFormData.entityHasCollectionMembers)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if it is missing required attribute headers" in {
            (Post(tsvImportPath, MockTSVFormData.entityUpdateMissingRequiredAttrs)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 200 OK if there's no data" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.entityHasNoRows)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has the full set of required attribute headers" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.entityUpdateWithRequiredAttrs)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has the full set of required attribute headers, plus optionals" in {
            stubRawlsService(HttpMethods.POST, batchUpsertPath, NoContent)
            (Post(tsvImportPath, MockTSVFormData.entityUpdateWithRequiredAndOptionalAttrs)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }
        }

        "an update-type TSV" - {
          "should 400 Bad Request if the entity type is unknown" in {
            (Post(tsvImportPath, MockTSVFormData.updateUnknownFirstColumnHeader)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if it contains duplicated entities to update" in {
            (Post(tsvImportPath, MockTSVFormData.updateHasDupes)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 400 Bad Request if it contains collection member headers" in {
            (Post(tsvImportPath, MockTSVFormData.updateHasCollectionMembers)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }
          }

          "should 200 OK even if it is missing required attribute headers" in {
            stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
            (Post(tsvImportPath, MockTSVFormData.updateMissingRequiredAttrs)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has the full set of required attribute headers" in {
            stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
            (Post(tsvImportPath, MockTSVFormData.updateWithRequiredAttrs)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 200 OK if it has the full set of required attribute headers, plus optionals" in {
            stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
            (Post(tsvImportPath, MockTSVFormData.updateWithRequiredAndOptionalAttrs)
              ~> dummyUserIdHeaders("1234")
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }
        }
      }
    }

//    "WorkspaceService BagIt Tests" - {
//      "should unbundle a bagit containing both participants and samples" in {
//       // bagitService()
//        //maybe don't stub out rawls service?
//        stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
//        val content = HttpEntity(MediaTypes.`application/json`, "{\"bagitURL\":\"http://localhost:9393/both.zip\",\"format\":\"TSV\"}")
//        (Post(bagitImportPath, BagitImportRequest("http://localhost:9393/both.zip","TSV"))
//          ~> dummyUserIdHeaders("1234")
//          ~> sealRoute(workspaceRoutes)) ~> check {
//          status should equal(OK)
//        }
//      }
//
//      "should 400 if a bagit doesn't have either participants or samples" in {
//        bagitService()
//        stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
//        (Post(bagitImportPath, HttpEntity(MediaTypes.`application/json`, s"""{"bagitURL":"http://localhost:$bagitServerPort/neither.zip", "format":"TSV" }"""))
//          ~> dummyUserIdHeaders("1234")
//          ~> sealRoute(workspaceRoutes)) ~> check {
//          status should equal(BadRequest)
//        }
//      }
//
//      "should 400 if a bagit request has an invalid format" in {
//        bagitService()
//        stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
//        (Post(bagitImportPath, HttpEntity(MediaTypes.`application/json`, s"""{"bagitURL":"http://localhost:$bagitServerPort/both.zip", "format":"garbage" }"""))
//          ~> dummyUserIdHeaders("1234")
//          ~> sealRoute(workspaceRoutes)) ~> check {
//          status should equal(BadRequest)
//        }
//      }
//    }

    "Workspace updateAttributes tests" - {
      "when calling any method other than PATCH on workspaces/*/*/updateAttributes path" - {
        "should receive a MethodNotAllowed error" in {
          List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.GET, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(updateAttributesPath, HttpEntity(MediaTypes.`application/json`, "{}")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }

      "when calling PATCH on workspaces/*/*/updateAttributes path" - {
        "should 400 Bad Request if the payload is malformed" in {
          (Patch(updateAttributesPath, HttpEntity(MediaTypes.`application/json`, "{{{"))
            ~> dummyUserIdHeaders("1234")
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
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(!this.searchDao.indexDocumentInvoked, "Should not be indexing an unpublished WS")
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
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(this.searchDao.indexDocumentInvoked, "Should have republished this published WS when changing attributes")
          }
        }

      }
    }

    "Workspace setAttributes tests" - {
      "when calling any method other than PATCH on workspaces/*/*/setAttributes path" - {
        "should receive a MethodNotAllowed error" in {
          List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.GET, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(setAttributesPath, HttpEntity(MediaTypes.`application/json`, "{}")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }

      "when calling PATCH on workspaces/*/*/setAttributes path" - {
        "should 400 Bad Request if the payload is malformed" in {
          (Patch(setAttributesPath, HttpEntity(MediaTypes.`application/json`, "{{{"))
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 200 OK if the payload is ok" in {
          (Patch(setAttributesPath,
            HttpEntity(MediaTypes.`application/json`, """{"description": "something",
                                                        | "array": [1, 2, 3]
                                                        | }""".stripMargin))
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(!this.searchDao.indexDocumentInvoked, "Should not be indexing an unpublished WS")
          }
        }

        "should republish if the document is already published" in {

          (Patch(workspacesRoot + "/%s/%s/setAttributes".format(WorkspaceApiServiceSpec.publishedWorkspace.namespace, WorkspaceApiServiceSpec.publishedWorkspace.name),
            HttpEntity(MediaTypes.`application/json`, """{"description": "something",
                                                        | "array": [1, 2, 3]
                                                        | }""".stripMargin))
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(this.searchDao.indexDocumentInvoked, "Should have republished this published WS when changing attributes")
          }
        }

      }

      "when calling POST on the workspaces/*/*/importAttributesTSV path" - {
        "should 200 OK if it has the correct headers and valid internals" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.addNewWorkspaceAttributes)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          })
        }

        "should 400 Bad Request if first row does not start with \"workspace\"" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.wrongHeaderWorkspaceAttributes)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(BadRequest)
          })
        }

        "should 400 Bad Request if there are more names than values" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.tooManyNamesWorkspaceAttributes)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(BadRequest)
          })
        }

        "should 400 Bad Request if there are more values than names" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.tooManyValuesWorkspaceAttributes)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(BadRequest)
          })
        }

        "should 400 Bad Request if there are more than 2 rows" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.tooManyRowsWorkspaceAttributes)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(BadRequest)
          })
        }

        "should 400 Bad Request if there are fewer than 2 rows" in {
          (Post(tsvAttributesImportPath, MockTSVFormData.tooFewRowsWorkspaceAttributes)
            ~> dummyUserIdHeaders("1234")
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
              new RequestBuilder(method)(storageCostEstimatePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
                status should be (MethodNotAllowed)
              }
          }
        }
      }

      "when calling GET on workspaces/*/*/storageCostEstimate" - {
        "should return 200 with result for good request" in {
          Get(storageCostEstimatePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should be (OK)
            responseAs[WorkspaceStorageCostEstimate].estimate should be ("$2.56")
          }
        }
      }
    }
  }
}
