package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.mock.MockTSVFormData
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{MethodConfiguration, EntityCreateResult, WorkspaceEntity, WorkspaceName}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.testkit.ScalatestRouteTest

class WorkspaceServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest
  with Matchers with WorkspaceService with FireCloudRequestBuilding {

  def actorRefFactory = system

  private final val ApiPrefix = "/workspaces"

  override def beforeAll(): Unit = {
    MockWorkspaceServer.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockWorkspaceServer.stopWorkspaceServer()
  }

  "WorkspaceService" - {

    val workspaceIngest = WorkspaceName(
      name = Some(randomAlpha()),
      namespace = Some(randomAlpha()))
    val invalidWorkspaceIngest = WorkspaceName(
      name = Option.empty,
      namespace = Option.empty)

    val tsvImportPath = ApiPrefix + "/%s/%s/importEntities".format(
      MockWorkspaceServer.mockValidWorkspace.namespace.get,
      MockWorkspaceServer.mockValidWorkspace.name.get)

    val attributeUpdatePath = ApiPrefix + "/%s/%s/updateAttributes".format(
      MockWorkspaceServer.mockValidWorkspace.namespace.get,
      MockWorkspaceServer.mockValidWorkspace.name.get)

    "when calling POST on the workspaces path with a valid WorkspaceIngest" - {
      "valid workspace is returned" in {
        Post(ApiPrefix, workspaceIngest) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
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
        val path = "/workspaces/%s/%s/methodconfigs".format(
          MockWorkspaceServer.mockInvalidWorkspace.namespace.get,
          MockWorkspaceServer.mockInvalidWorkspace.name.get)
        Get(path) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check{
          status should be(NotFound)
        }
      }
    }

    "when calling GET on the list method configurations with a valid workspace namespace/name"- {
      "OK response and a list of at list one Method Configuration is returned" in {
        val path = "/workspaces/%s/%s/methodconfigs".format(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        Get(path) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check{
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
        Post(ApiPrefix, invalidWorkspaceIngest) ~> dummyAuthHeaders ~>sealRoute(routes) ~> check {
          status should be(BadRequest)
        }
      }
    }

    "when calling GET on the workspaces path" - {
      "valid workspaces are returned" in {
        Get(ApiPrefix) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
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

    "when calling the GET workspace path" - {
      "a workspace is returned for a valid ID" in {
        val path = "/workspaces/%s/%s".format(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        Get(path) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
          responseAs[WorkspaceEntity].namespace shouldNot be(empty)
        }
      }

      "a 404 is returned for an invalid ID" in {
        val path = "/workspaces/%s/%s".format(
          MockWorkspaceServer.mockInvalidWorkspace.namespace.get,
          MockWorkspaceServer.mockInvalidWorkspace.name.get)
        Get(path) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
        }
      }
    }

    "when calling the DELETE workspace path" - {
      "a valid response is returned for a valid ID" in {
        val path = "/workspaces/%s/%s".format(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        Delete(path) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
        }
      }

      "a 404 is returned for an invalid ID" in {
        val path = "/workspaces/%s/%s".format(
          MockWorkspaceServer.mockInvalidWorkspace.namespace.get,
          MockWorkspaceServer.mockInvalidWorkspace.name.get)
        Delete(path) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
        }
      }
    }

    "when calling GET on the workspaces/*/*/acl path" - {
      "valid ACL is returned" in {
        val path = "/workspaces/%s/%s/acl".format(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        Get(path) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val acl = responseAs[List[Map[String, String]]]
          acl shouldNot be(empty)
        }
      }
    }

    "when calling PATCH on the workspaces/*/*/acl path" - {
      "OK response is returned" in {
        val path = "/workspaces/%s/%s/acl".format(
          MockWorkspaceServer.mockValidWorkspace.namespace.get,
          MockWorkspaceServer.mockValidWorkspace.name.get)
        Patch(path, MockWorkspaceServer.mockWorkspaceACL) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling PATCH on the workspaces/*/*/updateAttributes to add/update an attribute" - {
      "OK response is returned" in {
        (Patch(attributeUpdatePath, MockWorkspaceServer.mockUpdateAttributeOperation)
        ~> dummyAuthHeaders
        ~> sealRoute(routes)) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling POST on the workspaces/*/*/importEntities path" - {
      "should 400 Bad Request if the TSV type is missing" in {
        (Post(tsvImportPath, MockTSVFormData.missingTSVType)
          ~> dummyAuthHeaders
          ~> sealRoute(routes)) ~> check {
          status should equal(BadRequest)
        }
      }

      "should 400 Bad Request if the TSV type is nonsense" in {
        (Post(tsvImportPath, MockTSVFormData.nonexistentTSVType)
          ~> dummyAuthHeaders
          ~> sealRoute(routes)) ~> check {
          status should equal(BadRequest)
        }
      }

      "should 400 Bad Request if the TSV entity type doesn't end in _id" in {
        (Post(tsvImportPath, MockTSVFormData.malformedEntityType)
          ~> dummyAuthHeaders
          ~> sealRoute(routes)) ~> check {
          status should equal(BadRequest)
        }
      }

      "a membership-type TSV" - {
        "should 400 Bad Request if the entity type is unknown" in {
          (Post(tsvImportPath, MockTSVFormData.membershipUnknownFirstColumnHeader)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 400 Bad Request if the entity type is not a collection type" in {
          (Post(tsvImportPath, MockTSVFormData.membershipNotCollectionType)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 400 Bad Request if the collection members header is missing" in {
          (Post(tsvImportPath, MockTSVFormData.membershipMissingMembersHeader)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 400 Bad Request if it contains other headers than its collection members" in {
          (Post(tsvImportPath, MockTSVFormData.membershipExtraAttributes)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 200 OK if it has the correct headers and valid internals" in {
          (Post(tsvImportPath, MockTSVFormData.membershipValid)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(OK)
          }
        }
      }

      "an entity-type TSV" - {
        "should 400 Bad Request if the entity type is unknown" in {
          (Post(tsvImportPath, MockTSVFormData.entityUnknownFirstColumnHeader)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 400 Bad Request if it contains duplicated entities to update" in {
          (Post(tsvImportPath, MockTSVFormData.entityHasDupes)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 400 Bad Request if it contains collection member headers" in {
          (Post(tsvImportPath, MockTSVFormData.entityHasCollectionMembers)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 400 Bad Request if it is missing required attribute headers" in {
          (Post(tsvImportPath, MockTSVFormData.entityUpdateMissingRequiredAttrs)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 200 OK if it has the full set of required attribute headers" in {
          (Post(tsvImportPath, MockTSVFormData.entityUpdateWithRequiredAttrs)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(OK)
          }
        }

        "should 200 OK if it has the full set of required attribute headers, plus optionals" in {
          (Post(tsvImportPath, MockTSVFormData.entityUpdateWithRequiredAndOptionalAttrs)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(OK)
          }
        }
      }

      "an update-type TSV" - {
        "should 400 Bad Request if the entity type is unknown" in {
          (Post(tsvImportPath, MockTSVFormData.updateUnknownFirstColumnHeader)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 400 Bad Request if it contains duplicated entities to update" in {
          (Post(tsvImportPath, MockTSVFormData.updateHasDupes)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 400 Bad Request if it contains collection member headers" in {
          (Post(tsvImportPath, MockTSVFormData.updateHasCollectionMembers)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(BadRequest)
          }
        }

        "should 200 OK even if it is missing required attribute headers" in {
          (Post(tsvImportPath, MockTSVFormData.updateMissingRequiredAttrs)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(OK)
          }
        }

        "should 200 OK if it has the full set of required attribute headers" in {
          (Post(tsvImportPath, MockTSVFormData.updateWithRequiredAttrs)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(OK)
          }
        }

        "should 200 OK if it has the full set of required attribute headers, plus optionals" in {
          (Post(tsvImportPath, MockTSVFormData.updateWithRequiredAndOptionalAttrs)
            ~> dummyAuthHeaders
            ~> sealRoute(routes)) ~> check {
            status should equal(OK)
          }
        }
      }
    }
  }

}
