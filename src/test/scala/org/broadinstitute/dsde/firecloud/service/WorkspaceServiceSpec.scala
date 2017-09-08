package org.broadinstitute.dsde.firecloud.service
import akka.testkit.TestActorRef
import org.broadinstitute.dsde.firecloud.model.{AccessToken, WithAccessToken, WorkspaceDeleteResponse}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.rawls.model.{AttributeBoolean, AttributeName, AttributeString, Workspace, _}
import org.scalatest.BeforeAndAfterEach
import spray.http.{OAuth2BearerToken, StatusCode, StatusCodes}

import scala.concurrent.Await
import scala.concurrent.duration._


class WorkspaceServiceSpec extends BaseServiceSpec with BeforeAndAfterEach {

  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app)

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
      val workspaceNamespace = "publishedowner"
      val rqComplete = Await.
        result(ws.deleteWorkspace(workspaceNamespace, workspaceName), Duration.Inf).
        asInstanceOf[RequestComplete[WorkspaceDeleteResponse]]
      val workspaceDeleteResponse = rqComplete.response
      workspaceDeleteResponse.message.isDefined should be (true)
      workspaceDeleteResponse.message.get should include (ws.unPublishSuccessMessage(workspaceNamespace, workspaceName))
    }

    "should delete a published workspace successfully even if un-publish fails" in {
      val workspaceNamespace = "publishedownerfailindexdelete"
      val rqComplete = Await.
        result(ws.deleteWorkspace(workspaceNamespace, workspaceName), Duration.Inf).
        asInstanceOf[RequestComplete[WorkspaceDeleteResponse]]
      val workspaceDeleteResponse = rqComplete.response
      workspaceDeleteResponse.message.isDefined should be (true)
      workspaceDeleteResponse.message.get should include (ws.unPublishErrorMessage(workspaceNamespace, workspaceName))
    }

  }

}

