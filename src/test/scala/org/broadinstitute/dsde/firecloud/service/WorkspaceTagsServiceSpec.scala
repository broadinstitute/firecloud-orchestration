package org.broadinstitute.dsde.firecloud.service

import java.util.concurrent.ConcurrentHashMap

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.{EntityService, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.MockRawlsDAO
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.webservice.WorkspaceApiService
import org.broadinstitute.dsde.rawls.model
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, AttributeUpdateOperation, RemoveListMember}
import org.broadinstitute.dsde.rawls.model._
import org.joda.time.DateTime
import org.scalatest.{Assertions, BeforeAndAfterEach}
import akka.http.scaladsl.model.StatusCodes._
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.server.Route.{seal => sealRoute}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Unit tests for workspace tag apis.
  *
  * Remember that the responses from the tag apis are sorted, so the expected values in unit
  * tests may look funny - it's the sorting.
  */
class WorkspaceTagsServiceSpec extends BaseServiceSpec with WorkspaceApiService with BeforeAndAfterEach with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  // Mock remote endpoints
  private final val workspacesRoot = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath

  def workspaceTagsPath(ns: String = "namespace", name: String = "name") = workspacesRoot + "/%s/%s/tags".format(ns, name)

  // use the MockTagsRawlsDao for these tests.
  val testApp = app.copy(rawlsDAO = new MockTagsRawlsDao)
  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(testApp)
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService = PermissionReportService.constructor(testApp)
  val entityServiceConstructor: (ModelSchema) => EntityService = EntityService.constructor(app)

  private def randUUID = java.util.UUID.randomUUID.toString

  "Workspace tag APIs" - {
    "when GET-ting tags" - {
      "should return the pre-existing tags" in {
        Get(workspaceTagsPath("threetags")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be(OK)
          responseAs[List[String]] should be(List("bar", "baz", "foo"))
        }
      }
      "should return a single tag as a list" in {
        Get(workspaceTagsPath("onetag")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be(OK)
          responseAs[List[String]] should be(List("wibble"))
        }
      }
      "should extract tags out of mixed attributes" in {
        Get(workspaceTagsPath("mixedattrs")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be(OK)
          responseAs[List[String]] should be(List("blep", "boop"))
        }
      }
      "should return empty list if no tags" in {
        Get(workspaceTagsPath("notags")) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be(OK)
          responseAs[List[String]] should be(List.empty[String])
        }
      }
    }
    "when PUT-ting tags" - {
      "should reject a bad payload" in {
        val payload = List(true, false, true, false)
        Put(workspaceTagsPath("put"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be(BadRequest)
        }
      }
      "should set multiple tags" in {
        testPut(
          List("two", "four", "six"),
          List("four", "six", "two")
        )
      }
      "should set a single tag" in {
        testPut(
          List("single"),
          List("single")
        )
      }
      "should set the empty list" in {
        testPut(
          List.empty[String],
          List.empty[String]
        )
      }
      "should overwrite tags" in {
        testPut(
          List("first", "pass"),
          List("first", "pass"),
          List("second", "should", "overwrite"),
          List("overwrite", "second", "should")
        )
      }
      "should overwrite the empty list" in {
        testPut(
          List("first", "pass"),
          List("first", "pass"),
          List.empty[String],
          List.empty[String]
        )
      }
    }
    "when PATCH-ing tags" - {
      "should reject a bad payload" in {
        val payload = List(true, false, true, false)
        Patch(workspaceTagsPath("put"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be(BadRequest)
        }
      }
      "should set multiple tags from scratch" in {
        testPatch(
          List("two", "four", "six"),
          List("four", "six", "two")
        )
      }
      "should set a single tag from scratch" in {
        testPatch(
          List("single"),
          List("single")
        )
      }
      "should set the empty list from scratch" in {
        testPatch(
          List.empty[String],
          List.empty[String]
        )
      }
      "should add tags when none exist" in {
        testPatch(
          List.empty[String],
          List.empty[String],
          List("second", "should", "create"),
          List("create", "second", "should")
        )
      }
      "should add tags to pre-existing" in {
        testPatch(
          List("first", "pass"),
          List("first", "pass"),
          List("second", "should", "append"),
          List("append", "first", "pass", "second", "should")
        )
      }
      "should merge when patching tag values that already exist" in {
        testPatch(
          List("first", "pass"),
          List("first", "pass"),
          List("second", "pass", "merges"),
          List("first", "merges", "pass", "second")
        )
      }
      "should change nothing if all tags already exist" in {
        testPatch(
          List("existing", "tags"),
          List("existing", "tags"),
          List("existing", "tags"),
          List("existing", "tags")
        )
      }
      "should change nothing when adding the empty list" in {
        testPatch(
          List("first", "pass"),
          List("first", "pass"),
          List.empty[String],
          List("first", "pass")
        )
      }
    }
    "when DELETE-ing tags" - {
      "should reject a bad payload" in {
        val payload = List(true, false, true, false)
        Patch(workspaceTagsPath("put"), payload) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status should be(BadRequest)
        }
      }
      "removing multiple tags from scratch should be the empty list" in {
        testDelete(
          List("two", "four", "six"),
          List.empty[String]
        )
      }
      "removing a single tag from scratch should be the empty list" in {
        testDelete(
          List("single"),
          List.empty[String]
        )
      }
      "removing the empty list from scratch should be the empty list" in {
        testDelete(
          List.empty[String],
          List.empty[String]
        )
      }
      "removing all pre-existing tags should be the empty list" in {
        testDelete(
          List("pre", "existing", "tags"),
          List("existing", "pre", "tags"),
          List("pre", "existing", "tags"),
          List.empty[String]
        )
      }
      "removing tags that don't already exist should change nothing" in {
        testDelete(
          List("I", "am", "here"),
          List("am", "here", "I"),
          List("you", "are", "not"),
          List("am", "here", "I")
        )
      }
      "removing the empty list should change nothing" in {
        testDelete(
          List("what", "is", "love"),
          List("is", "love", "what"),
          List.empty[String],
          List("is", "love", "what")
        )
      }
      "removing some of the tags that already exist should leave the rest untouched" in {
        testDelete(
          List("potatoes", "beans", "cauliflower"),
          List("beans", "cauliflower", "potatoes"),
          List("cauliflower"),
          List("beans", "potatoes")
        )
      }
      "removing a partially-overlapping set should do the right thing" in {
        testDelete(
          List("a", "b", "c", "d", "e"),
          List("a", "b", "c", "d", "e"),
          List("c", "d", "e", "f", "g"),
          List("a", "b")
        )
      }
    }
  }

  // ==========================================================================
  // helpers for tests
  // ==========================================================================
  private def testPut(tags: List[String], expected: List[String]) = {
    singlepassTest(tags, expected, Put)
  }
  private def testPut(firstTags: List[String], firstExpected: List[String], secondTags: List[String], secondExpected: List[String]) = {
    multipassTest(firstTags, firstExpected, Put, secondTags, secondExpected)
  }

  private def testPatch(tags: List[String], expected: List[String]) = {
    singlepassTest(tags, expected, Patch)
  }
  private def testPatch(firstTags: List[String], firstExpected: List[String], secondTags: List[String], secondExpected: List[String]) = {
    multipassTest(firstTags, firstExpected, Patch, secondTags, secondExpected)
  }

  private def testDelete(tags: List[String], expected: List[String]) = {
    singlepassTest(tags, expected, Delete)
  }
  private def testDelete(firstTags: List[String], firstExpected: List[String], secondTags: List[String], secondExpected: List[String]) = {
    multipassTest(firstTags, firstExpected, Delete, secondTags, secondExpected)
  }

  private def singlepassTest(tags: List[String], expected: List[String], method: RequestBuilder) = {
    val name = randUUID
    method(workspaceTagsPath(method.method.value.toLowerCase, name), tags) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
      status should be(OK)
      responseAs[List[String]] should be(expected)
    }
  }

  private def multipassTest(firstTags: List[String], firstExpected: List[String], secondMethod: RequestBuilder, secondTags: List[String], secondExpected: List[String]) = {
    val name = randUUID
    Put(workspaceTagsPath("put", name), firstTags) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
      status should be(OK)
      responseAs[List[String]] should be(firstExpected)

      secondMethod(workspaceTagsPath(secondMethod.method.value.toLowerCase, name), secondTags) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
        status should be(OK)
        responseAs[List[String]] should be(secondExpected)
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

  import scala.jdk.CollectionConverters._

  private var statefulTagMap = new ConcurrentHashMap[String, ListBuffer[String]]().asScala

  private val workspace = WorkspaceDetails(
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
    Some(Set.empty), //authdomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    None,
    Option(DateTime.now()),
    None,
    None
  )

  private def workspaceResponse(ws:WorkspaceDetails=workspace) = WorkspaceResponse(
    Some(WorkspaceAccessLevels.ProjectOwner),
    canShare = Some(false),
    canCompute = Some(true),
    catalog = Some(false),
    ws,
    Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)),
    Some(WorkspaceBucketOptions(false)),
    Some(Set.empty),
    None
  )


  private def workspaceFromState(ns: String, name: String) = {
    val tags = statefulTagMap.getOrElse(name, ListBuffer.empty[String])
    val tagAttrs = (tags map AttributeString).toSeq
    workspace.copy(attributes = Option(Map(
      AttributeName.withTagsNS() -> AttributeValueList(tagAttrs)
    )))
  }

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] = {
    // AttributeName.withTagsNS() -> AttributeValueList(Seq(AttributeString("foo"),AttributeString("bar")))
    ns match {
      case "notags" => Future.successful(workspaceResponse())
      case "onetag" => Future.successful(workspaceResponse(workspace.copy(attributes = Option(Map(
        AttributeName.withTagsNS() -> AttributeValueList(Seq(AttributeString("wibble")))
      )))))
      case "threetags" => Future.successful(workspaceResponse(workspace.copy(attributes = Option(Map(
        AttributeName.withTagsNS() -> AttributeValueList(Seq(AttributeString("foo"),AttributeString("bar"),AttributeString("baz")))
      )))))
      case "mixedattrs" => Future.successful(workspaceResponse(workspace.copy(attributes = Option(Map(
        AttributeName.withTagsNS() -> AttributeValueList(Seq(AttributeString("boop"),AttributeString("blep"))),
        AttributeName.withDefaultNS("someDefault") -> AttributeNumber(123),
        AttributeName.withLibraryNS("someLibrary") -> AttributeBoolean(true)
      )))))
      case "put" | "patch" | "delete" =>
        Future.successful(workspaceResponse(workspaceFromState(ns, name)))
      case _ =>
        Future.successful(workspaceResponse())
    }
  }

  override def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails] = {
    ns match {
      // unsafe casts throughout here - we want to throw exceptions if anything is the wrong type
      case "put" =>
        attributes match {
          case Seq(op:AddUpdateAttribute) =>
            val tags = op.addUpdateAttribute.asInstanceOf[AttributeValueList].list map {
              _.asInstanceOf[AttributeString].value
            }
            statefulTagMap.put(name, ListBuffer( tags:_* ))
          case _ => fail("Put operation should consist of one AddUpdateAttribute operation")
        }
      case "patch" =>
        assert( attributes.forall(_.isInstanceOf[AddListMember]),
          "Patch operation should consist of only AddListMember operations" )
        val newTags = attributes.map(_.asInstanceOf[AddListMember].newMember.asInstanceOf[AttributeString].value)
        val currentTags = statefulTagMap.getOrElse(name, ListBuffer.empty[String])
        val finalTags = currentTags ++ newTags
        statefulTagMap.put(name, finalTags)
      case "delete" =>
        assert( attributes.forall(_.isInstanceOf[RemoveListMember]),
          "Delete operation should consist of only AddListMember operations" )
        val removeTags = attributes.map(_.asInstanceOf[RemoveListMember].removeMember.asInstanceOf[AttributeString].value)
        val currentTags = statefulTagMap.getOrElse(name, ListBuffer.empty[String])
        val finalTags = currentTags --= removeTags
        statefulTagMap.put(name, finalTags)
      case _ => fail("is the unit test correct?")
    }

    Future.successful(workspaceFromState(ns, name))
  }


}
