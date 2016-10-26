package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.mock.{MockUtils, MockTSVFormData}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.webservice.WorkspaceApiService

import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.scalatest.BeforeAndAfterEach
import spray.http.HttpMethods

import spray.http.StatusCodes._
import spray.json._

class WorkspaceApiServiceSpec extends BaseServiceSpec with WorkspaceApiService with BeforeAndAfterEach {

  def actorRefFactory = system

  val workspace = WorkspaceEntity(
    Some("namespace"),
    Some("name")
  )

  // Mock remote endpoints
  private final val workspacesRoot = "api/workspaces"
  private final val workspacesPath = workspacesRoot + "/%s/%s".format(workspace.namespace.get, workspace.name.get)
  private final val methodconfigsPath = workspacesRoot + "/%s/%s/methodconfigs".format(workspace.namespace.get, workspace.name.get)
  private final val updateAttributesPath = workspacesRoot + "/%s/%s/updateAttributes".format(workspace.namespace.get, workspace.name.get)
  private final val aclPath = workspacesRoot + "/%s/%s/acl".format(workspace.namespace.get, workspace.name.get)
  private final val clonePath = workspacesRoot + "/%s/%s/clone".format(workspace.namespace.get, workspace.name.get)
  private final val lockPath = workspacesRoot + "/%s/%s/lock".format(workspace.namespace.get, workspace.name.get)
  private final val unlockPath = workspacesRoot + "/%s/%s/unlock".format(workspace.namespace.get, workspace.name.get)
  private final val bucketPath = workspacesRoot + "/%s/%s/checkBucketReadAccess".format(workspace.namespace.get, workspace.name.get)

  private final val workspaceBasePath = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  private final val tsvImportPath = "/" + workspacesRoot + "/%s/%s/importEntities".format(workspace.namespace.get, workspace.name.get)

  val workspaceServiceConstructor: (UserInfo) => WorkspaceService = WorkspaceService.constructor(app)

  var workspaceServer: ClientAndServer = _

  override def beforeAll(): Unit = {
    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)

    // Passthrough Responders

    // workspaces
    List(HttpMethods.GET, HttpMethods.POST) map {
      method =>
        workspaceServer
          .when(request().withMethod(method.name).withPath(workspacesRoot))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
    }
    // workspaces/%s/%s
    List(HttpMethods.GET, HttpMethods.DELETE) map {
      method =>
        workspaceServer
          .when(request().withMethod(method.name).withPath(workspacesPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
    }
    // workspaces/%s/%s/methodconfigs
    List(HttpMethods.GET, HttpMethods.POST) map {
      method =>
        workspaceServer
          .when(request().withMethod(method.name).withPath(methodconfigsPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
    }
    // workspaces/%s/%s/updateAttributes
    workspaceServer
      .when(request().withMethod("PATCH").withPath(updateAttributesPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    // workspaces/%s/%s/acl
    List(HttpMethods.GET, HttpMethods.PATCH) map {
      method =>
        workspaceServer
          .when(request().withMethod(method.name).withPath(aclPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
    }
    // workspaces/%s/%s/clone
    workspaceServer
      .when(request().withMethod("POST").withPath(clonePath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    // workspaces/%s/%s/lock
    workspaceServer
      .when(request().withMethod("PUT").withPath(lockPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    // workspaces/%s/%s/unlock
    workspaceServer
      .when(request().withMethod("PUT").withPath(unlockPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    // workspaces/%s/%s/checkBucketReadAccess
    workspaceServer
      .when(request().withMethod("GET").withPath(bucketPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    // Entity responders

    workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"${workspaceBasePath}/${workspace.namespace.get}/${workspace.name.get}/entities/batchUpsert")
          .withHeader(authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(NoContent.intValue)
          .withBody(rawlsErrorReport(NoContent).toJson.compactPrint)
      )

    workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"${workspaceBasePath}/${workspace.namespace.get}/${workspace.name.get}/entities/batchUpdate")
          .withHeader(authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(NoContent.intValue)
          .withBody(rawlsErrorReport(NoContent).toJson.compactPrint)
      )
  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
  }

  "WorkspaceService Passthrough Negative Tests" - {

    "Passthrough tests on the /workspaces path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)("/api/workspaces") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, POST methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.POST) map {
          method =>
            new RequestBuilder(method)("/api/workspaces/namespace/name") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/methodconfigs path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)("/api/workspaces/namespace/name/methodconfigs") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/updateAttributes path" - {
      "MethodNotAllowed error is returned for HTTP PUT, POST, GET, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)("/api/workspaces/namespace/name/updateAttributes") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/acl path" - {
      "MethodNotAllowed error is returned for HTTP PUT, POST, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.POST, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)("/api/workspaces/namespace/name/acl") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/clone path" - {
      "MethodNotAllowed error is returned for HTTP PUT, PATCH, GET, DELETE methods" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)("/api/workspaces/namespace/name/clone") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/lock path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, GET, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)("/api/workspaces/namespace/name/lock") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/unlock path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, GET, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)("/api/workspaces/namespace/name/unlock") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "Passthrough tests on the /workspaces/segment/segment/checkBucketReadAccess path" - {
      "MethodNotAllowed error is returned for HTTP POST, PATCH, PUT, DELETE methods" in {
        List(HttpMethods.POST, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)("/api/workspaces/namespace/name/checkBucketReadAccess") ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

  }

  "WorkspaceService Passthrough Tests" - {

    "Passthrough tests on the /workspaces path" - {
      "MethodNotAllowed error is not returned for HTTP GET and POST methods" in {
        List(HttpMethods.GET, HttpMethods.POST) map {
          method =>
            new RequestBuilder(method)(workspacesRoot) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status shouldNot equal(MethodNotAllowed)
            }
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s path" - {
      "MethodNotAllowed error is not returned for HTTP GET and DELETE methods" in {
        List(HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)(workspacesRoot) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status shouldNot equal(MethodNotAllowed)
            }
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/methodconfigs path" - {
      "MethodNotAllowed error is not returned for HTTP GET and POST methods" in {
        List(HttpMethods.GET, HttpMethods.POST) map {
          method =>
            new RequestBuilder(method)(methodconfigsPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status shouldNot equal(MethodNotAllowed)
            }
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/updateAttributes path" - {
      "MethodNotAllowed error is not returned for HTTP PATCH method" in {
        Patch(updateAttributesPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status shouldNot equal(MethodNotAllowed)
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/acl path" - {
      "MethodNotAllowed error is not returned for HTTP GET and PATCH methods" in {
        List(HttpMethods.GET, HttpMethods.PATCH) map {
          method =>
            new RequestBuilder(method)(aclPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status shouldNot equal(MethodNotAllowed)
            }
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/clone path" - {
      "MethodNotAllowed error is not returned for POST method" in {
        Post(clonePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status shouldNot equal(MethodNotAllowed)
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/lock path" - {
      "MethodNotAllowed error is not returned for PUT method" in {
        Put(lockPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status shouldNot equal(MethodNotAllowed)
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/unlock path" - {
      "MethodNotAllowed error is not returned for PUT method" in {
        Put(unlockPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status shouldNot equal(MethodNotAllowed)
        }
      }
    }


    "Passthrough tests on the /workspaces/%s/%s/checkBucketReadAccess path" - {
      "MethodNotAllowed error is not returned for GET method" in {
        Get(bucketPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
          status shouldNot equal(MethodNotAllowed)
        }
      }
    }

  }

  "WorkspaceService TSV Tests" - {

    "when calling any method other than POST on workspaces/*/*/importEntities path" - {
      "should receive a MethodNotAllowed error" in {
        List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)(tsvImportPath, MockTSVFormData.membershipValid) ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "when calling POST on the workspaces/*/*/importEntities path" - {
      "should 400 Bad Request if the TSV type is missing" in {
        (Post(tsvImportPath, MockTSVFormData.missingTSVType)
          ~> dummyUserIdHeaders("1234")
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(BadRequest)
          errorReportCheck("FireCloud", BadRequest)
        }
      }

      "should 400 Bad Request if the TSV type is nonsense" in {
        (Post(tsvImportPath, MockTSVFormData.nonexistentTSVType)
          ~> dummyUserIdHeaders("1234")
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(BadRequest)
          errorReportCheck("FireCloud", BadRequest)
        }
      }

      "should 400 Bad Request if the TSV entity type doesn't end in _id" in {
        (Post(tsvImportPath, MockTSVFormData.malformedEntityType)
          ~> dummyUserIdHeaders("1234")
          ~> sealRoute(workspaceRoutes)) ~> check {
          status should equal(BadRequest)
          errorReportCheck("FireCloud", BadRequest)
        }
      }

      "a membership-type TSV" - {
        "should 400 Bad Request if the entity type is unknown" in {
          (Post(tsvImportPath, MockTSVFormData.membershipUnknownFirstColumnHeader)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if the entity type is not a collection type" in {
          (Post(tsvImportPath, MockTSVFormData.membershipNotCollectionType)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if the collection members header is missing" in {
          (Post(tsvImportPath, MockTSVFormData.membershipMissingMembersHeader)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if it contains other headers than its collection members" in {
          (Post(tsvImportPath, MockTSVFormData.membershipExtraAttributes)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 200 OK if it has the correct headers and valid internals" in {
          (Post(tsvImportPath, MockTSVFormData.membershipValid)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
          }
        }
      }

      "an entity-type TSV" - {
        "should 400 Bad Request if the entity type is unknown" in {
          (Post(tsvImportPath, MockTSVFormData.entityUnknownFirstColumnHeader)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if it contains duplicated entities to update" in {
          (Post(tsvImportPath, MockTSVFormData.entityHasDupes)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if it contains collection member headers" in {
          (Post(tsvImportPath, MockTSVFormData.entityHasCollectionMembers)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if it is missing required attribute headers" in {
          (Post(tsvImportPath, MockTSVFormData.entityUpdateMissingRequiredAttrs)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 200 OK if it has the full set of required attribute headers" in {
          (Post(tsvImportPath, MockTSVFormData.entityUpdateWithRequiredAttrs)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
          }
        }

        "should 200 OK if it has the full set of required attribute headers, plus optionals" in {
          (Post(tsvImportPath, MockTSVFormData.entityUpdateWithRequiredAndOptionalAttrs)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
          }
        }
      }

      "an update-type TSV" - {
        "should 400 Bad Request if the entity type is unknown" in {
          (Post(tsvImportPath, MockTSVFormData.updateUnknownFirstColumnHeader)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if it contains duplicated entities to update" in {
          (Post(tsvImportPath, MockTSVFormData.updateHasDupes)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 400 Bad Request if it contains collection member headers" in {
          (Post(tsvImportPath, MockTSVFormData.updateHasCollectionMembers)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(BadRequest)
            errorReportCheck("FireCloud", BadRequest)
          }
        }

        "should 200 OK even if it is missing required attribute headers" in {
          (Post(tsvImportPath, MockTSVFormData.updateMissingRequiredAttrs)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
          }
        }

        "should 200 OK if it has the full set of required attribute headers" in {
          (Post(tsvImportPath, MockTSVFormData.updateWithRequiredAttrs)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
          }
        }

        "should 200 OK if it has the full set of required attribute headers, plus optionals" in {
          (Post(tsvImportPath, MockTSVFormData.updateWithRequiredAndOptionalAttrs)
            ~> dummyUserIdHeaders("1234")
            ~> sealRoute(workspaceRoutes)) ~> check {
            status should equal(OK)
          }
        }
      }
    }
  }

}
