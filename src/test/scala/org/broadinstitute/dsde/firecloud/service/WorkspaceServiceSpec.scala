package org.broadinstitute.dsde.firecloud.service
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.testkit.TestActorRef
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.{AccessToken, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudException}
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.rawls.model._
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


class WorkspaceServiceSpec extends BaseServiceSpec with BeforeAndAfterEach {

  val customApp = Application(agoraDao, googleServicesDao, ontologyDao, consentDao, new MockRawlsDeleteWSDAO(), samDao, new MockSearchDeleteWSDAO(), new MockResearchPurposeSupport, thurloeDao, new MockShareLogDAO, new MockImportServiceDAO, shibbolethDao)

  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(customApp)

  lazy val ws: WorkspaceService = workspaceServiceConstructor(AccessToken(OAuth2BearerToken("")))

  override def beforeEach(): Unit = {
    searchDao.reset()
  }

  override def afterEach(): Unit = {
    searchDao.reset()
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
        List("workspace:e", "d", "b", "c", "a", "f").mkString("\t"),
        List("\"this\thas\ttabs\tin\tit\"", "escape quo\"te", 1.23, "", "true", "[\"v6\",999,true]").mkString("\t")).mkString("\n")

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
        asInstanceOf[RequestComplete[(StatusCode, Option[String])]]
      val (status, workspaceDeleteResponse) = rqComplete.response
      workspaceDeleteResponse.isDefined should be (true)
      status should be (StatusCodes.Accepted)
    }

    "should delete a published workspace successfully" in {
      val workspaceNamespace = "unpublishsuccess"
      val rqComplete = Await.
        result(ws.deleteWorkspace(workspaceNamespace, workspaceName), Duration.Inf).
        asInstanceOf[RequestComplete[(StatusCode, Option[String])]]
      val (status, workspaceDeleteResponse) = rqComplete.response
      workspaceDeleteResponse.isDefined should be (true)
      workspaceDeleteResponse.get should include (ws.unPublishSuccessMessage(workspaceNamespace, workspaceName))
      status should be (StatusCodes.Accepted)
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

  override def deleteWorkspace(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[Option[String]] = {
    Future.successful(Some("Your Google bucket 'bucketId' will be deleted within 24h."))
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
      case "projectowner" => Future(WorkspaceResponse(Some(WorkspaceAccessLevels.ProjectOwner), canShare = Some(true), canCompute = Some(true), catalog = Some(false), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "unpublishsuccess" => Future(WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare = Some(true), canCompute = Some(true), catalog = Some(false), unpublishsuccess, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "unpublishfailure" => Future(WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare = Some(true), canCompute = Some(true), catalog = Some(false), unpublishfailure, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case _ => Future(WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare = Some(true), canCompute = Some(true), catalog = Some(false), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
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
        deleteDocumentInvoked.set(false)
        throw new FireCloudException(s"Failed to remove document with id $id from elastic search")
      case _ => deleteDocumentInvoked.set(true)
    }
  }

}

