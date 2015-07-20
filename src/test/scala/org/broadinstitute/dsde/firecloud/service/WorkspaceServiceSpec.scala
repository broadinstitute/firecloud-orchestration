package org.broadinstitute.dsde.firecloud.service

import com.wordnik.swagger.annotations.ApiModelProperty
import org.broadinstitute.dsde.firecloud
import org.broadinstitute.dsde.firecloud.MockServers
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{WorkspaceEntity, WorkspaceIngest,MethodConfigurationEntity }
import org.broadinstitute.dsde.firecloud.model.{EntityCreateResult, WorkspaceEntity, WorkspaceIngest}
import org.broadinstitute.dsde.vault.common.openam.OpenAMSession
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.http.{FormData, HttpCookie}
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.testkit.ScalatestRouteTest

import scala.annotation.meta.field

class WorkspaceServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest with Matchers with WorkspaceService {

  def actorRefFactory = system

  private final val ApiPrefix = "/workspaces"

  override def beforeAll(): Unit = {
    MockServers.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockServers.stopWorkspaceServer()
  }

  "WorkspaceService" - {

    val openAMSession = OpenAMSession(()).futureValue(timeout(Span(5, Seconds)), interval(scaled(Span(0.5, Seconds))))
    val token = openAMSession.cookies.head.content
    val workspaceIngest = WorkspaceIngest(
      name = Some(MockServers.randomAlpha()),
      namespace = Some(MockServers.randomAlpha()))
    val invalidWorkspaceIngest = WorkspaceIngest(
      name = Option.empty,
      namespace = Option.empty)

    "when calling POST on the workspaces path with a valid WorkspaceIngest" - {
      "valid workspace is returned" in {
        Post(ApiPrefix, workspaceIngest) ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~> sealRoute(routes) ~> check {
          status should equal(Created)
          val entity = responseAs[WorkspaceEntity]
          entity.name shouldNot be(Option.empty)
          entity.namespace shouldNot be(Option.empty)
          entity.createdBy shouldNot be(Option.empty)
          entity.createdDate shouldNot be(Option.empty)
          entity.attributes should be(Some(Map.empty))
        }
      }
    }


    //EAS
  "when calling GET on the list method configurations with a valid workspace name and a valid list of method configurations"- {
      "valid empty list is returned with either nonexistent workspace name or namespace" in {
        Get(ApiPrefix + "/nonexistent/nonexistent/methodconfigs") ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~> sealRout(routes) ~> check{
          status should be(BadRequest)
        }
      }
    }

//EAS
    "when calling GET on the list method configurations with a valid workspace name and a valid list of method configurations"- {
      "valid list of at list one length returned with given existing workspace name and namespace" in {
        Get(ApiPrefix + "/nonexistent/nonexistent/methodconfigs") ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~> sealRout(routes) ~> check{
          status should equal(OK)
          val methodconfigurations = responseAs[List[MethodConfigurationEntity]]
          methodconfigurations  shouldNot be(empty)
          methodconfigurations foreach {
            mc: MethodConfigurationEntity => mc.name shouldNot be(empty)
          }
        }
      }
    }




    "when calling POST on the workspaces path without a valid authentication token" - {
      "Unauthorized (401) response is returned" in {
        Post(ApiPrefix, workspaceIngest) ~> sealRoute(routes) ~> check {
          status should equal(Unauthorized)
        }
      }
    }

    "when calling POST on the workspaces path with an invalid workspace ingest entity" - {
      "Bad Request (400) response is returned" in {
        Post(ApiPrefix, invalidWorkspaceIngest) ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~>sealRoute(routes) ~> check {
          status should be(BadRequest)
        }
      }
    }

    "when calling GET on the workspaces path" - {
      "valid workspaces are returned" in {
        Get(ApiPrefix) ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val workspaces = responseAs[List[WorkspaceEntity]]
          workspaces shouldNot be(empty)
          workspaces foreach {
            w: WorkspaceEntity => w.namespace shouldNot be(empty)
          }
        }
      }
    }

    "when calling PUT on the workspaces path" - {
      "MethodNotAllowed error is returned" in {
        Put(ApiPrefix) ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          responseAs[String] === "HTTP method not allowed, supported methods: GET"
        }
      }
    }

    "when calling POST on the workspaces/*/*/importEntitiesJSON path with valid but empty form data" - {
      "OK response is returned" in {
        (Post(ApiPrefix + "/namespace/name/importEntitiesJSON", MockServers.mockEmptyEntityFormData)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling POST on the workspaces/*/*/importEntitiesJSON path with invalid form data" - {
      "415 Unsupported Media Type response is returned" in {
        (Post(ApiPrefix + "/namespace/name/importEntitiesJSON", """{}""")
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(UnsupportedMediaType)
        }
      }
    }

    "when calling POST on the workspaces/*/*/importEntitiesJSON path with non-empty data" - {
      "OK response is returned, with correct results for each entity" in {
        (Post(ApiPrefix + "/namespace/name/importEntitiesJSON", MockServers.mockNonEmptyEntityFormData)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(OK)
          responseAs[Seq[EntityCreateResult]].map(_.succeeded) should equal (MockServers.mockNonEmptySuccesses)
        }
      }
    }

  }

}
