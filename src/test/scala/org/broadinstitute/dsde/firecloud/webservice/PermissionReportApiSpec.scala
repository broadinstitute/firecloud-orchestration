package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpMethods, HttpResponse}
import org.broadinstitute.dsde.firecloud.{EntityService, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{MockAgoraDAO, MockRawlsDAO}
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository._
import org.broadinstitute.dsde.firecloud.model.{OrchMethodConfigurationName, ModelSchema, PermissionReport, PermissionReportRequest, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, PermissionReportService, WorkspaceService}
import org.broadinstitute.dsde.rawls.model.{MethodConfigurationShort, MethodRepoMethod, _}
import org.scalatest.BeforeAndAfterEach
import akka.http.scaladsl.model.StatusCodes._
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.server.Route.{seal => sealRoute}

import scala.concurrent.{ExecutionContext, Future}

class PermissionReportApiSpec extends BaseServiceSpec with WorkspaceApiService with BeforeAndAfterEach with SprayJsonSupport {

  import PermissionReportMockMethods._

  def actorRefFactory = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val testApp = app.copy(agoraDAO=new PermissionReportMockAgoraDAO(), rawlsDAO=new PermissionReportMockRawlsDAO())

  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(testApp)
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService = PermissionReportService.constructor(testApp)
  val entityServiceConstructor: (ModelSchema) => EntityService = EntityService.constructor(app)

  def permissionReportPath(ns:String,name:String) = s"/api/workspaces/$ns/$name/permissionReport"

  "Permission Report API" - {

    "should only accept POST" in {
      List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
        method =>
          new RequestBuilder(method)(permissionReportPath("foo","bar")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
      }
    }

    "should reject malformed input" in {
      // endpoint expects an object; send it an array
      val badPayload = Seq("one","two","three")
      Post(permissionReportPath("foo","bar"), badPayload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(BadRequest)
      }
    }

    "should return 404 if workspace doesn't exist" in {
      val payload = PermissionReportRequest(None,None)
      Post(permissionReportPath("notfound","notfound"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(NotFound)
      }
    }

    "should accept correctly-formed input" in {
      val payload = PermissionReportRequest(Some(Seq("foo")),Some(Seq(OrchMethodConfigurationName("ns","name"))))
      Post(permissionReportPath("foo","bar"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
      }
    }

    "should treat both users and configs as optional inputs" in {
      val payload = PermissionReportRequest(None,None)
      Post(permissionReportPath("foo","bar"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
      }
    }

    "should return all users and all configs if caller omits inputs" in {
      val payload = PermissionReportRequest(None,None)
      Post(permissionReportPath("foo","bar"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
        val report = responseAs[PermissionReport]
        assertResult(Set("alice@example.com","bob@example.com","carol@example.com")) {report.workspaceACL.keySet}

        val expectedConfigsNoAcls = Map(
          OrchMethodConfigurationName("configns1", "configname1") -> Some(mockMethod1),
          OrchMethodConfigurationName("configns2", "configname2") -> Some(mockMethod2),
          OrchMethodConfigurationName("configns3", "configname3") -> Some(mockMethod3)
        )

        assertResult(expectedConfigsNoAcls) {(report.referencedMethods map {
          x => x.referencedBy -> x.method
        }).toMap}
      }
    }

    "should filter users if caller specifies" in {
      val payload = PermissionReportRequest(Some(Seq("carol@example.com")),None)
      Post(permissionReportPath("foo","bar"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
        val report = responseAs[PermissionReport]
        assertResult(Set("carol@example.com")) {report.workspaceACL.keySet}

        val expectedConfigsNoAcls = Map(
          OrchMethodConfigurationName("configns1", "configname1") -> Some(mockMethod1),
          OrchMethodConfigurationName("configns2", "configname2") -> Some(mockMethod2),
          OrchMethodConfigurationName("configns3", "configname3") -> Some(mockMethod3)
        )

        assertResult(expectedConfigsNoAcls) {(report.referencedMethods map {
          x => x.referencedBy -> x.method
        }).toMap}
      }
    }

    "should filter configs if caller specifies" in {
      val payload = PermissionReportRequest(None,Some(Seq(OrchMethodConfigurationName("configns2","configname2"))))
      Post(permissionReportPath("foo","bar"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
        val report = responseAs[PermissionReport]
        assertResult(Set("alice@example.com","bob@example.com","carol@example.com")) {report.workspaceACL.keySet}

        val expectedConfigsNoAcls = Map(
          OrchMethodConfigurationName("configns2", "configname2") -> Some(mockMethod2)
        )

        assertResult(expectedConfigsNoAcls) {(report.referencedMethods map {
          x => x.referencedBy -> x.method
        }).toMap}
      }
    }

    "should filter both users and configs if caller specifies" in {
      val payload = PermissionReportRequest(Some(Seq("carol@example.com")),Some(Seq(OrchMethodConfigurationName("configns2","configname2"))))
      Post(permissionReportPath("foo","bar"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
        val report = responseAs[PermissionReport]
        assertResult(Set("carol@example.com")) {report.workspaceACL.keySet}

        val expectedConfigsNoAcls = Map(
          OrchMethodConfigurationName("configns2", "configname2") -> Some(mockMethod2)
        )

        assertResult(expectedConfigsNoAcls) {(report.referencedMethods map {
          x => x.referencedBy -> x.method
        }).toMap}
      }
    }

    "should propagate method-specific error message from Agora" in {
      val payload = PermissionReportRequest(None,None)
      Post(permissionReportPath("foo","bar"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
        val report = responseAs[PermissionReport]

        val withError = report.referencedMethods.find(_.method.contains(mockMethod3))

        assert(withError.isDefined, "test target method should exist")
        assert(withError.get.message.isDefined, "error message should exist")
      }
    }

    "should omit a caller-specified user if user doesn't exist in the workspace" in {
      val payload = PermissionReportRequest(Some(Seq("carol@example.com", "dan@example.com")),None)
      Post(permissionReportPath("foo","bar"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
        val report = responseAs[PermissionReport]
        assertResult(Set("carol@example.com")) {report.workspaceACL.keySet}

        val expectedConfigsNoAcls = Map(
          OrchMethodConfigurationName("configns1", "configname1") -> Some(mockMethod1),
          OrchMethodConfigurationName("configns2", "configname2") -> Some(mockMethod2),
          OrchMethodConfigurationName("configns3", "configname3") -> Some(mockMethod3)
        )

        assertResult(expectedConfigsNoAcls) {(report.referencedMethods map {
          x => x.referencedBy -> x.method
        }).toMap}
      }
    }

    "should omit a caller-specified config if config doesn't exist in the workspace" in {
      val payload = PermissionReportRequest(None,
        Some(Seq(
          OrchMethodConfigurationName("configns2","configname2"),
          OrchMethodConfigurationName("confignsZZZ","confignameZZZ")
        ))
      )
      Post(permissionReportPath("foo","bar"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
        val report = responseAs[PermissionReport]
        assertResult(Set("alice@example.com","bob@example.com","carol@example.com")) {report.workspaceACL.keySet}

        val expectedConfigsNoAcls = Map(
          OrchMethodConfigurationName("configns2", "configname2") -> Some(mockMethod2)
        )

        assertResult(expectedConfigsNoAcls) {(report.referencedMethods map {
          x => x.referencedBy -> x.method
        }).toMap}
      }
    }

    "should return empty workspace ACLs but still get method info if caller is not owner of workspace" in {
      val payload = PermissionReportRequest(None,None)
      Post(permissionReportPath("notowner","notowner"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should equal(OK)
        val report = responseAs[PermissionReport]

        assert(report.workspaceACL.isEmpty)

        val expectedConfigsNoAcls = Map(
          OrchMethodConfigurationName("configns1", "configname1") -> Some(mockMethod1),
          OrchMethodConfigurationName("configns2", "configname2") -> Some(mockMethod2),
          OrchMethodConfigurationName("configns3", "configname3") -> Some(mockMethod3)
        )

        assertResult(expectedConfigsNoAcls) {(report.referencedMethods map {
          x => x.referencedBy -> x.method
        }).toMap}
      }
    }

  }
}

class PermissionReportMockRawlsDAO extends MockRawlsDAO {

  val mockACL = WorkspaceACL(Map(
    "alice@example.com" -> AccessEntry(WorkspaceAccessLevels.Owner, false, false, true),
    "bob@example.com" -> AccessEntry(WorkspaceAccessLevels.Write, false, false, true),
    "carol@example.com" -> AccessEntry(WorkspaceAccessLevels.Read, false, true, false)
  ))

  val mockConfigs = Seq(
    AgoraConfigurationShort("configname1", "participant", MethodRepoMethod("methodns1", "methodname1", 1), "configns1"),
    AgoraConfigurationShort("configname2", "sample", MethodRepoMethod("methodns2", "methodname2", 2), "configns2"),
    AgoraConfigurationShort("configname3", "participant", MethodRepoMethod("methodns3", "methodname3", 3), "configns3")
  )

  override def getWorkspaceACL(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceACL] = {
    ns match {
      case "notfound" => Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(NotFound, "Not Found response from Mock")))
      case "notowner" => Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(Forbidden, "Forbidden response from Mock")))
      case _ => Future.successful(mockACL)
    }
  }

  override def getAgoraMethodConfigs(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[Seq[AgoraConfigurationShort]] = {
    Future.successful(mockConfigs)
  }

}

object PermissionReportMockMethods {
  val mockMethod1 = Method(Some("methodns1"), Some("methodname1"), Some(1), managers=Some(Seq("alice@example.com")), public=Some(true))
  val mockMethod2 = Method(Some("methodns2"), Some("methodname2"), Some(2), managers=Some(Seq("bob@example.com")), public=Some(false))
  val mockMethod3 = Method(Some("methodns3"), Some("methodname3"), Some(3), public=Some(false))
}

class PermissionReportMockAgoraDAO extends MockAgoraDAO {

  import PermissionReportMockMethods._

  val mockEntityAccessControlList = List(
    EntityAccessControlAgora(mockMethod1,
      Seq(
        AgoraPermission(Some("alice@example.com"), Some(ACLNames.ListOwner)),
        AgoraPermission(Some("bob@example.com"), Some(ACLNames.ListReader)),
        AgoraPermission(Some("public"), Some(ACLNames.ListReader))
      ),
      None),
    EntityAccessControlAgora(mockMethod2,
      Seq(
        AgoraPermission(Some("bob@example.com"), Some(ACLNames.ListOwner)),
        AgoraPermission(Some("carol@example.com"), Some(ACLNames.ListReader)),
        AgoraPermission(Some("public"), Some(List.empty[String]))
      ),
      None),
    EntityAccessControlAgora(mockMethod3,
      Seq.empty[AgoraPermission],
      Some("this method's mock response has an error"))
  )

  override def getMultiEntityPermissions(entityType: AgoraEntityType.Value, entities: List[Method])(implicit userInfo: UserInfo) = {
    Future.successful(mockEntityAccessControlList)
  }

}
