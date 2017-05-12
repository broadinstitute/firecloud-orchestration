package org.broadinstitute.dsde.firecloud.service
import akka.actor.ActorRefFactory
import akka.testkit.TestActorRef

import scala.concurrent.duration._
import org.broadinstitute.dsde.firecloud.model.{AccessToken, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestCompleteWithHeaders}
import spray.http.{HttpHeader, OAuth2BearerToken, StatusCode, StatusCodes}

import scala.concurrent.Await


class WorkspaceServiceSpec extends BaseServiceSpec {
 //val ws = new WorkspaceService(new AccessToken(OAuth2BearerToken("")), app.rawlsDAO, app.thurloeDAO)

  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app)

  lazy val ws: WorkspaceService = TestActorRef(WorkspaceService.props(workspaceServiceConstructor, new AccessToken(OAuth2BearerToken("")))).underlyingActor


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



}

