package org.broadinstitute.dsde.firecloud.service
import akka.testkit.TestActorRef
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.{Application, FireCloudException}
import org.broadinstitute.dsde.firecloud.model.{AccessToken, WithAccessToken, WorkspaceDeleteResponse}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.rawls.model.{AttributeBoolean, AttributeName, AttributeString, _}
import org.scalatest.BeforeAndAfterEach
import spray.http.{OAuth2BearerToken, StatusCode, StatusCodes}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._


class WorkspaceServiceSpec extends BaseServiceSpec with BeforeAndAfterEach {

  val customApp = Application(agoraDao, googleServicesDao, ontologyDao, consentDao, new MockRawlsDeleteWSDAO(), samDao, new MockSearchDeleteWSDAO(), new MockResearchPurposeSupport, thurloeDao, new MockLogitDAO, new MockShareLogDAO)

  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(customApp)

  lazy val ws: WorkspaceService = TestActorRef(WorkspaceService.props(workspaceServiceConstructor, AccessToken(OAuth2BearerToken("")))).underlyingActor

  override def beforeEach(): Unit = {
    searchDao.reset
  }

  override def afterEach(): Unit = {
    searchDao.reset
  }

  "export workspace attributes as TSV " - {
    "export valid tsv" in {
      val rqComplete = Await.result(ws.exportWorkspaceAttributesTSV("attributes", "n", "fn"), Duration.Inf)
        .asInstanceOf[RequestCompleteWithHeaders[(StatusCode, String)]]
      val (status, tsvString) = rqComplete.response

      assertResult(StatusCodes.OK) {
        status
      }

      val tsvReturnString = List(
        List("workspace:f", "d", "b", "a", "e", "c").mkString("\t"),
        List("[\"v6\",999,true]", "escape quo\\\"te", 1.23, "true", "v1", "").mkString("\t")).mkString("\n")

      assertResult(tsvReturnString) {
        tsvString
      }
    }

  }

  "delete workspace" - {

    val workspaceName = "name"

    "should delete an unpublished workspace successfully" in {
      val workspaceNamespace = "projectowner"
      val rqComplete = Await.
        result(ws.deleteWorkspace(workspaceNamespace, workspaceName), Duration.Inf).
        asInstanceOf[RequestComplete[WorkspaceDeleteResponse]]
      val workspaceDeleteResponse = rqComplete.response
      workspaceDeleteResponse.message.isDefined should be (true)
    }

    "should delete a published workspace successfully" in {
      val workspaceNamespace = "unpublishsuccess"
      val rqComplete = Await.
        result(ws.deleteWorkspace(workspaceNamespace, workspaceName), Duration.Inf).
        asInstanceOf[RequestComplete[WorkspaceDeleteResponse]]
      val workspaceDeleteResponse = rqComplete.response
      workspaceDeleteResponse.message.isDefined should be (true)
      workspaceDeleteResponse.message.get should include (ws.unPublishSuccessMessage(workspaceNamespace, workspaceName))
    }

    "should not delete a published workspace if un-publish fails" in {
      val workspaceNamespace = "unpublishfailure"
      val rqComplete = Await.
        result(ws.deleteWorkspace(workspaceNamespace, workspaceName), Duration.Inf).
        asInstanceOf[RequestComplete[(StatusCode, ErrorReport)]]
      val (status, error) = rqComplete.response
      status should be (StatusCodes.InternalServerError)
    }

  }
}

/*
 * Mock out DAO classes specific to this test class.
 * Override the chain of methods that are called within these service tests to isolate functionality.
 */
class MockRawlsDeleteWSDAO(implicit val executionContext: ExecutionContext) extends MockRawlsDAO {

  override def deleteWorkspace(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[WorkspaceDeleteResponse] = {
    Future.successful(WorkspaceDeleteResponse(Some("Your Google bucket 'bucketId' will be deleted within 24h.")))
  }

  private val unpublishsuccess = publishedRawlsWorkspaceWithAttributes.copy(
    namespace = "unpublishsuccess",
    name = "name",
    workspaceId = "unpublishsuccess"
  )

  private val unpublishfailure = publishedRawlsWorkspaceWithAttributes.copy(
    namespace = "unpublishfailure",
    name = "name",
    workspaceId = "unpublishfailure"
  )

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] = {
    ns match {
      case "attributes" => Future(rawlsWorkspaceResponseWithAttributes)
      case "projectowner" => Future(WorkspaceResponse(WorkspaceAccessLevels.ProjectOwner, canShare = true, canCompute=true, catalog=false, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), WorkspaceBucketOptions(false), Set.empty))
      case "unpublishsuccess" => Future(WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare = true, canCompute=true, catalog=false, unpublishsuccess, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), WorkspaceBucketOptions(false), Set.empty))
      case "unpublishfailure" => Future(WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare = true, canCompute=true, catalog=false, unpublishfailure, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), WorkspaceBucketOptions(false), Set.empty))
      case _ => Future(WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare = true, canCompute=true, catalog=false, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), WorkspaceBucketOptions(false), Set.empty))
    }
  }

  override def updateLibraryAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails] = {
    ns match {
      case "projectowner" => Future(newWorkspace)
      case "unpublishsuccess" => Future(publishedRawlsWorkspaceWithAttributes)
      case "unpublishfailure" => Future(unpublishfailure)
      case _ => Future(newWorkspace)
    }
  }

}

class MockSearchDeleteWSDAO extends MockSearchDAO {

  override def deleteDocument(id: String): Unit = {
    id match {
      case "unpublishfailure" =>
        deleteDocumentInvoked = false
        throw new FireCloudException(s"Failed to remove document with id $id from elastic search")
      case _ => deleteDocumentInvoked = true
    }
  }

}

