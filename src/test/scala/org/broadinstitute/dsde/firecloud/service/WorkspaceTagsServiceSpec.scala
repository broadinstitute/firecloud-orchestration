package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.MockRawlsDAO
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.webservice.WorkspaceApiService
import org.broadinstitute.dsde.rawls.model._
import org.joda.time.DateTime
import org.scalatest.BeforeAndAfterEach
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Future


class WorkspaceTagsServiceSpec extends BaseServiceSpec with WorkspaceApiService with BeforeAndAfterEach {

  def actorRefFactory = system



  // Mock remote endpoints
  private final val workspacesRoot = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  def workspaceTagsPath(ns:String="namespace", name:String="name") = workspacesRoot + "/%s/%s/tags".format(ns,name)

  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(
    app.copy(rawlsDAO = new MockTagsRawlsDao))

  "Workspace tag APIs" - {
    "when GETting tags" - {
      "should return the pre-existing tags" in {
        Get(workspaceTagsPath("threetags")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be (OK)
          responseAs[List[String]] should be (List("bar","baz","foo"))
        }
      }
      "should return a single tag as a list" in {
        Get(workspaceTagsPath("onetag")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be (OK)
          responseAs[List[String]] should be (List("wibble"))
        }
      }
      "should extract tags out of mixed attributes" in {
        Get(workspaceTagsPath("mixedattrs")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be (OK)
          responseAs[List[String]] should be (List("blep","boop"))
        }
      }
      "should return empty list if no tags" in {
        Get(workspaceTagsPath("notags")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be (OK)
          responseAs[List[String]] should be (List.empty[String])
        }
      }
    }
  }
}


class MockTagsRawlsDao extends MockRawlsDAO {

  val workspace = Workspace(
    "namespace",
    "name",
    None,
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

  def workspaceResponse(ws:Workspace=workspace) = WorkspaceResponse(
    WorkspaceAccessLevels.ProjectOwner,
    canShare = false,
    catalog=false,
    ws,
    WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0),
    List.empty
  )

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] = {
    // AttributeName.withTagsNS() -> AttributeValueList(Seq(AttributeString("foo"),AttributeString("bar")))
    ns match {
      case "notags" => Future.successful(workspaceResponse())
      case "onetag" => Future.successful(workspaceResponse(workspace.copy(attributes = Map(
        AttributeName.withTagsNS() -> AttributeValueList(Seq(AttributeString("wibble")))
      ))))
      case "threetags" => Future.successful(workspaceResponse(workspace.copy(attributes = Map(
        AttributeName.withTagsNS() -> AttributeValueList(Seq(AttributeString("foo"),AttributeString("bar"),AttributeString("baz")))
      ))))
      case "mixedattrs" => Future.successful(workspaceResponse(workspace.copy(attributes = Map(
        AttributeName.withTagsNS() -> AttributeValueList(Seq(AttributeString("boop"),AttributeString("blep"))),
        AttributeName.withDefaultNS("someDefault") -> AttributeNumber(123),
        AttributeName.withLibraryNS("someLibrary") -> AttributeBoolean(true)
      ))))
      case _ => Future.successful(workspaceResponse())
    }
  }

}
