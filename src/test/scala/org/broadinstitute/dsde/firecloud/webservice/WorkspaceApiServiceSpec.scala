package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route.{seal => sealRoute}

import org.broadinstitute.dsde.firecloud.dataaccess.LegacyFileTypes.{FILETYPE_PFB, FILETYPE_TDR}
import org.broadinstitute.dsde.firecloud.dataaccess.{
  MockCwdsDAO,
  MockRawlsDAO,
  MockShareLogDAO,
  WorkspaceApiServiceSpecShareLogDAO
}
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
    Some(Map(AttributeName("library", "published") -> AttributeBoolean(true))), // attributes
    false, // locked
    Some(Set.empty), // authorizationDomain
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

class WorkspaceApiServiceSpec
    extends BaseServiceSpec
    with WorkspaceApiService
    with BeforeAndAfterEach
    with SprayJsonSupport {

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
    Some(Map()), // attributes
    false, // locked
    Some(Set.empty), // authorizationDomain
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
  final private val workspacesRoot = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  final private val workspacesPath = workspacesRoot + "/%s/%s".format(workspace.namespace, workspace.name)
  final private val methodconfigsPath =
    workspacesRoot + "/%s/%s/methodconfigs".format(workspace.namespace, workspace.name)
  final private val updateAttributesPath =
    workspacesRoot + "/%s/%s/updateAttributes".format(workspace.namespace, workspace.name)
  final private val setAttributesPath =
    workspacesRoot + "/%s/%s/setAttributes".format(workspace.namespace, workspace.name)
  final private val tsvAttributesImportPath =
    workspacesRoot + "/%s/%s/importAttributesTSV".format(workspace.namespace, workspace.name)
  final private val tsvAttributesExportPath =
    workspacesRoot + "/%s/%s/exportAttributesTSV".format(workspace.namespace, workspace.name)
  final private val batchUpsertPath = s"${workspacesRoot}/${workspace.namespace}/${workspace.name}/entities/batchUpsert"
  final private val aclPath = workspacesRoot + "/%s/%s/acl".format(workspace.namespace, workspace.name)
  final private val sendChangeNotificationPath =
    workspacesRoot + "/%s/%s/sendChangeNotification".format(workspace.namespace, workspace.name)
  final private val accessInstructionsPath =
    workspacesRoot + "/%s/%s/accessInstructions".format(workspace.namespace, workspace.name)
  final private val clonePath = workspacesRoot + "/%s/%s/clone".format(workspace.namespace, workspace.name)
  final private val lockPath = workspacesRoot + "/%s/%s/lock".format(workspace.namespace, workspace.name)
  final private val unlockPath = workspacesRoot + "/%s/%s/unlock".format(workspace.namespace, workspace.name)
  final private val bucketPath =
    workspacesRoot + "/%s/%s/checkBucketReadAccess".format(workspace.namespace, workspace.name)
  final private val tsvImportPath = workspacesRoot + "/%s/%s/importEntities".format(workspace.namespace, workspace.name)
  final private val tsvImportFlexiblePath =
    workspacesRoot + "/%s/%s/flexibleImportEntities".format(workspace.namespace, workspace.name)
  final private val pfbImportPath = workspacesRoot + "/%s/%s/importPFB".format(workspace.namespace, workspace.name)
  final private val importJobPath = workspacesRoot + "/%s/%s/importJob".format(workspace.namespace, workspace.name)
  final private val importJobStatusPath =
    workspacesRoot + "/%s/%s/importJob".format(workspace.namespace, workspace.name)
  final private val bucketUsagePath = s"$workspacesPath/bucketUsage"
  final private val usBucketStorageCostEstimatePath =
    workspacesRoot + "/%s/%s/storageCostEstimate".format("usBucketWorkspace", workspace.name)
  final private val europeWest1storageCostEstimatePath =
    workspacesRoot + "/%s/%s/storageCostEstimate".format("europeWest1BucketWorkspace", workspace.name)
  final private val tagAutocompletePath = s"$workspacesRoot/tags"
  final private val executionEngineVersionPath = "/version/executionEngine"

  private def catalogPath(ns: String = workspace.namespace, name: String = workspace.name) =
    workspacesRoot + "/%s/%s/catalog".format(ns, name)

  val localShareLogDao: MockShareLogDAO = new WorkspaceApiServiceSpecShareLogDAO

  // use a disabled cWDS for these tests; enabled cWDS has tests coverage elsewhere
  val mockCwdsDao: MockCwdsDAO = new MockCwdsDAO(enabled = false)

  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService =
    WorkspaceService.constructor(app.copy(shareLogDAO = localShareLogDao))
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService =
    PermissionReportService.constructor(app)
  val entityServiceConstructor: (ModelSchema) => EntityService =
    EntityService.constructor(app.copy(cwdsDAO = mockCwdsDao))

  val nihProtectedAuthDomain = ManagedGroupRef(RawlsGroupName("dbGapAuthorizedUsers"))

  val dummyUserId = "1234"

  val bucketLocation = "us-central1"

  val protectedRawlsWorkspace = WorkspaceDetails(
    "attributes",
    "att",
    "id",
    "", // bucketname
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "mb",
    Some(Map()), // attrs
    false,
    Some(Set(nihProtectedAuthDomain)), // authorizationDomain
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
    "", // bucketname
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "mb",
    Some(Map()), // attrs
    false,
    Some(Set(ManagedGroupRef(RawlsGroupName("secret_realm")))), // authorizationDomain
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
    "", // bucketname
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "mb",
    Some(Map()), // attrs
    false,
    Some(Set.empty), // authorizationDomain
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

  val protectedRawlsWorkspaceResponse = WorkspaceResponse(
    Some(WorkspaceAccessLevels.Owner),
    canShare = Some(false),
    canCompute = Some(true),
    catalog = Some(false),
    protectedRawlsWorkspace,
    Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)),
    Some(WorkspaceBucketOptions(false, bucketLocation)),
    Some(Set.empty),
    None
  )
  val authDomainRawlsWorkspaceResponse = WorkspaceResponse(
    Some(WorkspaceAccessLevels.Owner),
    canShare = Some(false),
    canCompute = Some(true),
    catalog = Some(false),
    authDomainRawlsWorkspace,
    Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)),
    Some(WorkspaceBucketOptions(false, bucketLocation)),
    Some(Set.empty),
    None
  )
  val nonAuthDomainRawlsWorkspaceResponse = WorkspaceResponse(
    Some(WorkspaceAccessLevels.Owner),
    canShare = Some(false),
    canCompute = Some(true),
    catalog = Some(false),
    nonAuthDomainRawlsWorkspace,
    Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)),
    Some(WorkspaceBucketOptions(false, bucketLocation)),
    Some(Set.empty),
    None
  )

  var rawlsServer: ClientAndServer = _

  /** Stubs the mock Rawls service to respond to a request. Used for testing passthroughs.
    *
    * @param method HTTP method to respond to
    * @param path   request path
    * @param status status for the response
    */
  def stubRawlsService(method: HttpMethod,
                       path: String,
                       status: StatusCode,
                       body: Option[String] = None,
                       query: Option[(String, String)] = None,
                       requestBody: Option[String] = None
  ): Unit = {
    rawlsServer.reset()
    val request = org.mockserver.model.HttpRequest
      .request()
      .withMethod(method.name)
      .withPath(path)
    if (query.isDefined) request.withQueryStringParameter(query.get._1, query.get._2)
    requestBody.foreach(request.withBody)
    val response = org.mockserver.model.HttpResponse
      .response()
      .withHeaders(MockUtils.header)
      .withStatusCode(status.intValue)
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
  def stubRawlsCreateWorkspace(namespace: String,
                               name: String,
                               authDomain: Set[ManagedGroupRef] = Set.empty
  ): (WorkspaceRequest, WorkspaceDetails) = {
    rawlsServer.reset()
    val rawlsRequest = WorkspaceRequest(namespace, name, Map(), Option(authDomain))
    val rawlsResponse = WorkspaceDetails(
      namespace,
      name,
      "foo",
      "bar",
      Some("wf-collection"),
      DateTime.now(),
      DateTime.now(),
      "bob",
      Some(Map()),
      false,
      Some(authDomain),
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
  def stubRawlsCloneWorkspace(namespace: String,
                              name: String,
                              authDomain: Set[ManagedGroupRef] = Set.empty,
                              attributes: Attributable.AttributeMap = Map()
  ): (WorkspaceRequest, WorkspaceDetails) = {
    rawlsServer.reset()
    val published: (AttributeName, AttributeBoolean) = AttributeName("library", "published") -> AttributeBoolean(false)
    val discoverable = AttributeName("library", "discoverableByGroups") -> AttributeValueEmptyList
    val rawlsRequest: WorkspaceRequest =
      WorkspaceRequest(namespace, name, attributes + published + discoverable, Option(authDomain))
    val rawlsResponse = WorkspaceDetails(
      namespace,
      name,
      "foo",
      "bar",
      Some("wf-collection"),
      DateTime.now(),
      DateTime.now(),
      "bob",
      Some(attributes + published + discoverable),
      false,
      Some(authDomain),
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
    stubRawlsService(HttpMethods.POST, clonePath, Created, Option(rawlsResponse.toJson.compactPrint))
    (rawlsRequest, rawlsResponse)
  }

  def stubRawlsServiceWithError(method: HttpMethod, path: String, status: StatusCode) = {
    rawlsServer.reset()
    rawlsServer
      .when(request().withMethod(method.name).withPath(path))
      .respond(
        org.mockserver.model.HttpResponse
          .response()
          .withHeaders(MockUtils.header)
          .withStatusCode(status.intValue)
          .withBody(rawlsErrorReport(status).toJson.compactPrint)
      )
  }

  override def beforeAll(): Unit =
    rawlsServer = startClientAndServer(MockUtils.workspaceServerPort)

  override def afterAll(): Unit =
    rawlsServer.stop

  override def beforeEach(): Unit =
    this.searchDao.reset()

  override def afterEach(): Unit =
    this.searchDao.reset()

  // there are many values in the response that in reality cannot be predicted
  // we will only compare the key details: namespace, name, authdomain, attributes
  def assertWorkspaceDetailsEqual(expected: WorkspaceDetails, actual: WorkspaceDetails) = {
    actual.namespace should equal(expected.namespace)
    actual.name should equal(expected.name)
    actual.attributes should equal(expected.attributes)
    actual.authorizationDomain should equal(expected.authorizationDomain)
  }

  "Workspace Non-passthrough Tests" - {

    "OK status is returned from PATCH on /workspaces/%s/%s/acl" in
      Patch(aclPath,
            List(WorkspaceACLUpdate("dummy@test.org", WorkspaceAccessLevels.NoAccess, Some(false)))
      ) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
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

      val orchestrationRequest: WorkspaceRequest =
        WorkspaceRequest("namespace", "name", Map(), Option(Set(nihProtectedAuthDomain)))
      Post(clonePath, orchestrationRequest) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(Created)
        assertWorkspaceDetailsEqual(rawlsResponse, responseAs[WorkspaceDetails])
      }
    }

    "When cloning a published workspace, the clone should not be published" in {
      val (_, rawlsResponse) = stubRawlsCloneWorkspace(
        "namespace",
        "name",
        attributes = Map(AttributeName("library", "published") -> AttributeBoolean(false),
                         AttributeName("library", "discoverableByGroups") -> AttributeValueEmptyList
        )
      )

      val published = AttributeName("library", "published") -> AttributeBoolean(true)
      val discoverable =
        AttributeName("library", "discoverableByGroups") -> AttributeValueList(Seq(AttributeString("all_broad_users")))
      val orchestrationRequest = WorkspaceRequest("namespace", "name", Map(published, discoverable))
      Post(clonePath, orchestrationRequest) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(Created)
        assertWorkspaceDetailsEqual(rawlsResponse, responseAs[WorkspaceDetails])
      }
    }

    "Catalog permission tests on /workspaces/.../.../catalog" - {
      "when calling PATCH" - {
        "should be Forbidden as reader" in {
          val content =
            HttpEntity(ContentTypes.`application/json`, "[ {\"email\": \"user@gmail.com\",\"catalog\": true} ]")
          new RequestBuilder(HttpMethods.PATCH)(catalogPath("reader"), content) ~> dummyUserIdHeaders(
            dummyUserId
          ) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(Forbidden)
          }
        }
        "should be Forbidden as writer" in {
          val content =
            HttpEntity(ContentTypes.`application/json`, "[ {\"email\": \"user@gmail.com\",\"catalog\": true} ]")
          new RequestBuilder(HttpMethods.PATCH)(catalogPath("unpublishedwriter"), content) ~> dummyUserIdHeaders(
            dummyUserId
          ) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(Forbidden)
          }
        }
        "should be OK as owner" in {
          val content =
            HttpEntity(ContentTypes.`application/json`, "[ {\"email\": \"user@gmail.com\",\"catalog\": true} ]")
          new RequestBuilder(HttpMethods.PATCH)(catalogPath(), content) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(
            workspaceRoutes
          ) ~> check {
            status should equal(OK)
            val expected = WorkspaceCatalogUpdateResponseList(Seq(WorkspaceCatalogResponse("userid", true)), Seq.empty)
            responseAs[WorkspaceCatalogUpdateResponseList] should equal(expected)

          }
        }
      }
      "when calling GET" - {
        "should be OK as reader" in
          new RequestBuilder(HttpMethods.GET)(catalogPath("reader")) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(
            workspaceRoutes
          ) ~> check {
            status should equal(OK)
          }
        "should be OK as writer" in
          new RequestBuilder(HttpMethods.GET)(catalogPath("unpublishedwriter")) ~> dummyUserIdHeaders(
            dummyUserId
          ) ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(OK)
          }
      }
    }

    "WorkspaceService TSV Tests" - {

      "when calling any method other than POST on workspaces/*/*/importEntities path" - {
        "should receive a MethodNotAllowed error" in {
          List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map { method =>
            new RequestBuilder(method)(tsvImportPath, MockTSVFormData.membershipValid) ~> dummyUserIdHeaders(
              dummyUserId
            ) ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
          }
        }
      }

      "when calling POST on the workspaces/*/*/importEntities path" - {
        "should 400 Bad Request if the TSV type is missing" in
          (Post(tsvImportPath, MockTSVFormData.missingTSVType)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }

        "should 400 Bad Request if the TSV type is nonsense" in
          (Post(tsvImportPath, MockTSVFormData.nonexistentTSVType)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }

        "should 400 Bad Request if the TSV entity type doesn't end in _id" in
          (Post(tsvImportPath, MockTSVFormData.malformedEntityType)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }

        "a membership-type TSV" - {
          "should 400 Bad Request if the entity type is unknown" in
            (Post(tsvImportPath, MockTSVFormData.membershipUnknownFirstColumnHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }

          "should 400 Bad Request if the entity type is not a collection type" in
            (Post(tsvImportPath, MockTSVFormData.membershipNotCollectionType)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }

          "should 400 Bad Request if the collection members header is missing" in
            (Post(tsvImportPath, MockTSVFormData.membershipMissingMembersHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }

          "should 400 Bad Request if it contains other headers than its collection members" in
            (Post(tsvImportPath, MockTSVFormData.membershipExtraAttributes)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
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
          "should 400 Bad Request if the entity type is unknown calling default import" in
            (Post(tsvImportPath, MockTSVFormData.entityUnknownFirstColumnHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }

          "should 200 OK if the entity type is unknown and calling flexible import" in
            (Post(tsvImportFlexiblePath, MockTSVFormData.entityUnknownFirstColumnHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }

          "should 400 Bad Request if it contains duplicated entities to update" in
            (Post(tsvImportPath, MockTSVFormData.entityHasDupes)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }

          "should 400 Bad Request if it contains collection member headers" in
            (Post(tsvImportPath, MockTSVFormData.entityHasCollectionMembers)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }

          "should 400 Bad Request if it is missing required attribute headers" in
            (Post(tsvImportPath, MockTSVFormData.entityUpdateMissingRequiredAttrs)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
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
          "should 400 BadRequest if the entity type is non-FC model with calling default import" in
            (Post(tsvImportPath, MockTSVFormData.updateNonModelFirstColumnHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }

          "should 200 OK if the entity type is non-FC model when calling the flexible import" in {
            stubRawlsService(HttpMethods.POST, s"$workspacesPath/entities/batchUpdate", NoContent)
            (Post(tsvImportFlexiblePath, MockTSVFormData.updateNonModelFirstColumnHeader)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(OK)
            }
          }

          "should 400 Bad Request if it contains duplicated entities to update" in
            (Post(tsvImportPath, MockTSVFormData.updateHasDupes)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
            }

          "should 400 Bad Request if it contains collection member headers" in
            (Post(tsvImportPath, MockTSVFormData.updateHasCollectionMembers)
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              errorReportCheck("FireCloud", BadRequest)
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

    "WorkspaceService importPFB Tests" - {

      "should bubble up 400 from cwds" in
        (Post(pfbImportPath, PFBImportRequest("https://bad.request.avro"))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(BadRequest)
          responseAs[String] should include("Bad request as reported by cwds")
        }

      "should bubble up 403 from cwds" in
        (Post(pfbImportPath, PFBImportRequest("https://forbidden.avro"))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(Forbidden)
          responseAs[String] should include("Missing Authorization: Bearer token in header")
        }
      "should propagate any other errors from cWDS" in
        // we use UnavailableForLegalReasons as a proxy for "some error we didn't expect"
        (Post(pfbImportPath, PFBImportRequest("https://its.lawsuit.time.avro"))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(UnavailableForLegalReasons)
          responseAs[String] should include("cwds message")
        }

      "should 202 (Accepted) if everything validated and cwds request was accepted" in {

        val pfbPath = "https://good.avro"

        val orchExpectedPayload = AsyncImportResponse(url = pfbPath,
                                                      jobId = "MockCwdsDAO will generate a random UUID",
                                                      workspace = WorkspaceName(workspace.namespace, workspace.name)
        )

        (Post(pfbImportPath, PFBImportRequest("https://good.avro"))
          ~> dummyUserIdHeaders(dummyUserId)
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(Accepted)
          val jobResponse = responseAs[AsyncImportResponse]
          jobResponse.url should be(orchExpectedPayload.url)
          jobResponse.workspace should be(orchExpectedPayload.workspace)
          jobResponse.jobId should not be empty
        }
      }

    }

    "WorkspaceService POST importJob Tests" - {

      List(FILETYPE_PFB, FILETYPE_TDR) foreach { filetype =>
        s"for filetype $filetype" - {

          "should bubble up 400 from cwds" in
            (Post(importJobPath, AsyncImportRequest("https://bad.request.avro", filetype))
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(BadRequest)
              responseAs[String] should include("Bad request as reported by cwds")
            }

          "should bubble up 403 from cwds" in
            (Post(importJobPath, AsyncImportRequest("https://forbidden.avro", filetype))
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(Forbidden)
              responseAs[String] should include("Missing Authorization: Bearer token in header")
            }
          "should propagate any other errors from cWDS" in
            // we use UnavailableForLegalReasons as a proxy for "some error we didn't expect"
            (Post(importJobPath, AsyncImportRequest("https://its.lawsuit.time.avro", filetype))
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(UnavailableForLegalReasons)
              responseAs[String] should include("cwds message")
            }

          "should 202 (Accepted) if everything validated and import request was accepted" in {

            val pfbPath = "https://good.avro"

            val orchExpectedPayload = AsyncImportResponse(url = pfbPath,
                                                          jobId = "MockCwdsDAO will generate a random UUID",
                                                          workspace = WorkspaceName(workspace.namespace, workspace.name)
            )

            (Post(importJobPath, AsyncImportRequest("https://good.avro", filetype))
              ~> dummyUserIdHeaders(dummyUserId)
              ~> sealRoute(workspaceRoutes)) ~> check {
              status should equal(Accepted)
              val jobResponse = responseAs[AsyncImportResponse]
              jobResponse.url should be(orchExpectedPayload.url)
              jobResponse.workspace should be(orchExpectedPayload.workspace)
              jobResponse.jobId should not be empty
            }
          }
        }
      }

    }

    "Workspace updateAttributes tests" - {
      "when calling any method other than PATCH on workspaces/*/*/updateAttributes path" - {
        "should receive a MethodNotAllowed error" in {
          List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.GET, HttpMethods.DELETE) map { method =>
            new RequestBuilder(method)(updateAttributesPath,
                                       HttpEntity(MediaTypes.`application/json`, "{}")
            ) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
          }
        }
      }

      "when calling PATCH on workspaces/*/*/updateAttributes path" - {
        "should 400 Bad Request if the payload is malformed" in
          (Patch(updateAttributesPath, HttpEntity(MediaTypes.`application/json`, "{{{"))
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
          }

        "should 200 OK if the payload is ok" in
          (Patch(
            updateAttributesPath,
            HttpEntity(
              MediaTypes.`application/json`,
              """[
                |  {
                |    "op": "AddUpdateAttribute",
                |    "attributeName": "library:dataCategory",
                |    "addUpdateAttribute": "test-attribute-value"
                |  }
                |]""".stripMargin
            )
          )
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(!this.searchDao.indexDocumentInvoked.get(), "Should not be indexing an unpublished WS")
          }

        "should republish if the document is already published" in
          (Patch(
            workspacesRoot + "/%s/%s/updateAttributes".format(WorkspaceApiServiceSpec.publishedWorkspace.namespace,
                                                              WorkspaceApiServiceSpec.publishedWorkspace.name
            ),
            HttpEntity(
              MediaTypes.`application/json`,
              """[
                |  {
                |    "op": "AddUpdateAttribute",
                |    "attributeName": "library:dataCategory",
                |    "addUpdateAttribute": "test-attribute-value"
                |  }
                |]""".stripMargin
            )
          )
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(this.searchDao.indexDocumentInvoked.get(),
                   "Should have republished this published WS when changing attributes"
            )
          }

      }
    }

    "Workspace setAttributes tests" - {
      "when calling any method other than PATCH on workspaces/*/*/setAttributes path" - {
        "should receive a MethodNotAllowed error" in {
          List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.GET, HttpMethods.DELETE) map { method =>
            new RequestBuilder(method)(setAttributesPath,
                                       HttpEntity(MediaTypes.`application/json`, "{}")
            ) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
          }
        }
      }

      "when calling PATCH on workspaces/*/*/setAttributes path" - {
        "should 400 Bad Request if the payload is malformed" in
          (Patch(setAttributesPath, HttpEntity(MediaTypes.`application/json`, "{{{"))
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
          }

        "should 200 OK if the payload is ok" in
          (Patch(
            setAttributesPath,
            HttpEntity(
              MediaTypes.`application/json`,
              """{"description": "something",
                | "array": [1, 2, 3]
                | }""".stripMargin
            )
          )
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(!this.searchDao.indexDocumentInvoked.get(), "Should not be indexing an unpublished WS")
          }

        "should republish if the document is already published" in
          (Patch(
            workspacesRoot + "/%s/%s/setAttributes".format(WorkspaceApiServiceSpec.publishedWorkspace.namespace,
                                                           WorkspaceApiServiceSpec.publishedWorkspace.name
            ),
            HttpEntity(
              MediaTypes.`application/json`,
              """{"description": "something",
                | "array": [1, 2, 3]
                | }""".stripMargin
            )
          )
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
            assert(this.searchDao.indexDocumentInvoked.get(),
                   "Should have republished this published WS when changing attributes"
            )
          }

      }

      "when calling POST on the workspaces/*/*/importAttributesTSV path" - {
        "should 200 OK if it has the correct headers and valid internals" in
          (Post(tsvAttributesImportPath, MockTSVFormData.addNewWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(OK)
            })

        "should 400 Bad Request if first row does not start with \"workspace\"" in
          (Post(tsvAttributesImportPath, MockTSVFormData.wrongHeaderWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(BadRequest)
            })

        "should 400 Bad Request if there are more names than values" in
          (Post(tsvAttributesImportPath, MockTSVFormData.tooManyNamesWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(BadRequest)
            })

        "should 400 Bad Request if there are more values than names" in
          (Post(tsvAttributesImportPath, MockTSVFormData.tooManyValuesWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(BadRequest)
            })

        "should 400 Bad Request if there are more than 2 rows" in
          (Post(tsvAttributesImportPath, MockTSVFormData.tooManyRowsWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(BadRequest)
            })

        "should 400 Bad Request if there are fewer than 2 rows" in
          (Post(tsvAttributesImportPath, MockTSVFormData.tooFewRowsWorkspaceAttributes)
            ~> dummyUserIdHeaders(dummyUserId)
            ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(BadRequest)
            })

      }
    }

    "Workspace storage cost estimate tests" - {
      "when calling any method other than GET on workspaces/*/*/storageCostEstimate" - {
        "should return 405 Method Not Allowed for anything other than GET" in {
          List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.PATCH, HttpMethods.DELETE) map { method =>
            new RequestBuilder(method)(usBucketStorageCostEstimatePath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(
              workspaceRoutes
            ) ~> check {
              status should be(MethodNotAllowed)
            }
          }
        }
      }

      "when calling GET on workspaces/*/*/storageCostEstimate" - {
        "should return 200 with result for us region" in
          Get(usBucketStorageCostEstimatePath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(
            workspaceRoutes
          ) ~> check {
            status should be(OK)
            // 256000000000 / (1024 * 1024 * 1024) * 0.004 + 102400000 / (1024 * 1024 * 1024) * 0.02
            responseAs[WorkspaceStorageCostEstimate].estimate should be("$0.96")
          }
      }

    }
  }
}
