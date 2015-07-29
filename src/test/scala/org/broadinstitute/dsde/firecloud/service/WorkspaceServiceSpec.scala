package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.mock.MockTSVFormData
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{MethodConfiguration, EntityCreateResult, WorkspaceEntity, WorkspaceIngest}
import org.broadinstitute.dsde.vault.common.openam.OpenAMSession
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.http.HttpCookie
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.testkit.ScalatestRouteTest

class WorkspaceServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest with Matchers with WorkspaceService {

  def actorRefFactory = system

  private final val ApiPrefix = "/workspaces"

  override def beforeAll(): Unit = {
    MockWorkspaceServer.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockWorkspaceServer.stopWorkspaceServer()
  }

  "WorkspaceService" - {

    val openAMSession = OpenAMSession(()).futureValue(timeout(Span(5, Seconds)), interval(scaled(Span(0.5, Seconds))))
    val token = openAMSession.cookies.head.content
    val workspaceIngest = WorkspaceIngest(
      name = Some(randomAlpha()),
      namespace = Some(randomAlpha()))
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

    "when calling GET on the list method configurations with an invalid workspace namespace/name"- {
      "Not Found is returned" in {
        val path = FireCloudConfig.Workspace.methodConfigsListPath.format(
          MockWorkspaceServer.mockInvalidWorkspace.namespace.get,
          MockWorkspaceServer.mockInvalidWorkspace.name.get)
        Get(path) ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~> sealRoute(routes) ~> check{
          status should be(NotFound)
        }
      }
    }

    "when calling GET on the list method configurations with a valid workspace namespace/name"- {
      "OK response and a list of at list one Method Configuration is returned" in {
        val path = FireCloudConfig.Workspace.methodConfigsListPath.format(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        Get(path) ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~> sealRoute(routes) ~> check{
          status should equal(OK)
          val methodconfigurations = responseAs[List[MethodConfiguration]]
          methodconfigurations shouldNot be(empty)
          methodconfigurations foreach {
            mc: MethodConfiguration => mc.name shouldNot be(empty)
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
        (Post(ApiPrefix + "/namespace/name/importEntitiesJSON", MockWorkspaceServer.mockEmptyEntityFormData)
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
        (Post(ApiPrefix + "/namespace/name/importEntitiesJSON", MockWorkspaceServer.mockNonEmptyEntityFormData)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(OK)
          responseAs[Seq[EntityCreateResult]].map(_.succeeded) should equal (MockWorkspaceServer.mockNonEmptySuccesses)
        }
      }
    }

    "when calling POST on the workspaces/*/*/importEntities path" - {

      "should 400 Bad Request if the first column header of the TSV is an unknown entity type" in {
        val path = FireCloudConfig.Workspace.importEntitiesPathFromWorkspace(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        (Post(path, MockTSVFormData.unknownFirstColumnHeader)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(BadRequest)
        }
      }

      //IGNORED - a collection-type TSV missing members header is currently indistinguishable from an entity-update TSV.
      //This test should be reinstated when we can distinguish them.
      "should 400 Bad Request if a collection-type TSV is missing its collection members header" ignore {
        val path = FireCloudConfig.Workspace.importEntitiesPathFromWorkspace(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        (Post(path, MockTSVFormData.collectionTypeWithMissingMembersHeader)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(BadRequest)
        }
      }

      //NOTE: this is equivalent to:
      // "should 400 Bad Request if an entity-update TSV has collection member attribute headers"
      //This implies that we can't distinguish between the two - which is exactly why both are treated as errors!
      "should 400 Bad Request if a collection-type TSV has other headers than its collection members" in {
        val path = FireCloudConfig.Workspace.importEntitiesPathFromWorkspace(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        (Post(path, MockTSVFormData.collectionTypeWithExtraAttributes)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(BadRequest)
        }
      }

      "should 200 OK if a collection-type TSV has the correct headers and valid internals" in {
        val path = FireCloudConfig.Workspace.importEntitiesPathFromWorkspace(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        (Post(path, MockTSVFormData.validCollection)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(OK)
        }
      }

      "should 400 Bad Request if an entity-update TSV has duplicated entities to update" in {
        val path = FireCloudConfig.Workspace.importEntitiesPathFromWorkspace(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        (Post(path, MockTSVFormData.dupedEntityUpdate)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(BadRequest)
        }
      }

      "should 400 Bad Request if an entity-update TSV is missing required attribute headers" in {
        val path = FireCloudConfig.Workspace.importEntitiesPathFromWorkspace(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        (Post(path, MockTSVFormData.entityUpdateMissingRequiredAttrs)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(BadRequest)
        }
      }

      "should 200 OK if an entity-update TSV is has the full set of required attribute headers" in {
        val path = FireCloudConfig.Workspace.importEntitiesPathFromWorkspace(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        (Post(path, MockTSVFormData.entityUpdateWithRequiredAttrs)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(OK)
        }
      }

      "should 200 OK if an entity-update TSV is has the full set of required attribute headers, plus optionals" in {
        val path = FireCloudConfig.Workspace.importEntitiesPathFromWorkspace(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        (Post(path, MockTSVFormData.entityUpdateWithRequiredAndOptionalAttrs)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))
          ~> sealRoute(routes)) ~> check {
          status should equal(OK)
        }
      }
    }
  }

}
