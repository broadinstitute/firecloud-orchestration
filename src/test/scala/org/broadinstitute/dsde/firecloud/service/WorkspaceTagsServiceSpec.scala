package org.broadinstitute.dsde.firecloud.service

import java.util.concurrent.ConcurrentHashMap

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.MockRawlsDAO
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.webservice.WorkspaceApiService
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, AttributeUpdateOperation, RemoveListMember}
import org.broadinstitute.dsde.rawls.model._
import org.joda.time.DateTime
import org.scalatest.{Assertions, BeforeAndAfterEach}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.collection.mutable.{Set => MutableSet}
import scala.concurrent.Future


class WorkspaceTagsServiceSpec extends BaseServiceSpec with WorkspaceApiService with BeforeAndAfterEach {

  def actorRefFactory = system

  // Mock remote endpoints
  private final val workspacesRoot = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  def workspaceTagsPath(ns:String="namespace", name:String="name") = workspacesRoot + "/%s/%s/tags".format(ns,name)

  // use the MockTagsRawlsDao for these tests.
  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(
    app.copy(rawlsDAO = new MockTagsRawlsDao))

  private def randUUID = java.util.UUID.randomUUID.toString

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
    "when PUTting tags" - {
      "should reject a bad payload" in {
        val payload = List(true,false,true,false)
        Put(workspaceTagsPath("put"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be (BadRequest)
        }
      }
      "should set multiple tags" in {
        val payload = List("two","four","six")
        val name = randUUID
        Put(workspaceTagsPath("put", name), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be (OK)
          responseAs[List[String]] should be (List("four","six","two"))
        }
      }
      "should set a single tag" in {
        val payload = List("single")
        val name = randUUID
        Put(workspaceTagsPath("put", name), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be (OK)
          responseAs[List[String]] should be (List("single"))
        }
      }
      "should set the empty list" in {
        val payload = List.empty[String]
        val name = randUUID
        Put(workspaceTagsPath("put", name), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be (OK)
          responseAs[List[String]] should be (List.empty[String])
        }
      }
      "should overwrite tags" in {
        val payload = List("first","pass")
        val name = randUUID
        Put(workspaceTagsPath("put", name), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be(OK)
          responseAs[List[String]] should be(List("first", "pass"))

          val overwrite = List("second", "should", "overwrite")
          Put(workspaceTagsPath("put", name), overwrite) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should be(OK)
            responseAs[List[String]] should be(List("overwrite", "second", "should"))
          }
        }
      }
      "should overwrite the empty list" in {
        val payload = List("first","pass")
        val name = randUUID
        Put(workspaceTagsPath("put", name), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be(OK)
          responseAs[List[String]] should be(List("first", "pass"))

          val overwrite = List.empty[String]
          Put(workspaceTagsPath("put", name), overwrite) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
            status should be(OK)
            responseAs[List[String]] should be(List.empty[String])
          }
        }
      }
    }
  }
}

/** An extension to MockRawlsDAO that has stateful behavior
  * useful for this class's unit tests.
  *
  * We only override the methods used by the tag apis.
  */
class MockTagsRawlsDao extends MockRawlsDAO with Assertions {

  import scala.collection.convert.decorateAsScala._

  private var statefulTagMap = new ConcurrentHashMap[String, MutableSet[String]]().asScala

  private val workspace = Workspace(
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

  private def workspaceResponse(ws:Workspace=workspace) = WorkspaceResponse(
    WorkspaceAccessLevels.ProjectOwner,
    canShare = false,
    catalog=false,
    ws,
    WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0),
    List.empty
  )


  private def workspaceFromState(ns: String, name: String) = {
    val tags = statefulTagMap.getOrElse(name, Set.empty[String])
    val tagAttrs = (tags map AttributeString).toSeq
    workspace.copy(attributes = Map(
      AttributeName.withTagsNS() -> AttributeValueList(tagAttrs)
    ))
  }

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
      case "put" | "patch" | "delete" =>
        Future.successful(workspaceResponse(workspaceFromState(ns, name)))
      case _ =>
        Future.successful(workspaceResponse())
    }
  }

  override def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace] = {
    ns match {
      case "put" =>
        attributes match {
          case Seq(op:AddUpdateAttribute) =>
            // unsafe casts here - we want this to throw an exception if anything is the wrong type
            val tags = op.addUpdateAttribute.asInstanceOf[AttributeValueList].list map {
              _.asInstanceOf[AttributeString].value
            }
            statefulTagMap.put(name, MutableSet( tags:_* ))
          case _ => fail("Put operation should consist of one AddUpdateAttribute operation")
        }
      case "patch" =>
        assert( attributes.forall(_.isInstanceOf[AddListMember]),
          "Patch operation should consist of only AddListMember operations" )
      case "delete" =>
        assert( attributes.forall(_.isInstanceOf[RemoveListMember]),
          "Patch operation should consist of only AddListMember operations" )
      case _ => fail("is the unit test correct?")
    }

    Future.successful(workspaceFromState(ns, name))
  }


}
