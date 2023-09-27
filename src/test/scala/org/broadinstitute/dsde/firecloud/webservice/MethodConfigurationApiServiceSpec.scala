package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{CopyConfigurationIngest, PublishConfigurationIngest}
import org.broadinstitute.dsde.firecloud.service.ServiceSpec
import org.broadinstitute.dsde.rawls.model.{GoogleProjectId, GoogleProjectNumber, RawlsBillingAccountName, WorkspaceDetails, WorkspaceState, WorkspaceVersions}
import org.joda.time.DateTime
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._

import scala.concurrent.ExecutionContext

class MethodConfigurationApiServiceSpec extends ServiceSpec with MethodConfigurationApiService with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  var workspaceServer: ClientAndServer = _
  private final val mockWorkspace = WorkspaceDetails(
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
    None,
    WorkspaceState.Ready
  )

  override def beforeAll(): Unit = {
    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)
    List(MethodConfigurationApiService.remoteTemplatePath, MethodConfigurationApiService.remoteInputsOutputsPath) map {
      path =>
        workspaceServer.when(
          request().withMethod("POST").withPath(path))
          .respond(org.mockserver.model.HttpResponse.response()
            .withHeaders(MockUtils.header).withStatusCode(OK.intValue))
    }
    List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.DELETE) map {
      method =>
        workspaceServer
          .when(request().withMethod(method.name).withPath(
            MethodConfigurationApiService.remoteMethodConfigPath(
              mockWorkspace.namespace,
              mockWorkspace.name,
              mockWorkspace.namespace,
              mockWorkspace.name)))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
    }
    workspaceServer
      .when(request().withMethod("POST").withPath(
        MethodConfigurationApiService.remoteMethodConfigRenamePath(
          mockWorkspace.namespace,
          mockWorkspace.name,
          mockWorkspace.namespace,
          mockWorkspace.name)))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    workspaceServer
      .when(request().withMethod("GET").withPath(
        MethodConfigurationApiService.remoteMethodConfigValidatePath(
          mockWorkspace.namespace,
          mockWorkspace.name,
          mockWorkspace.namespace,
          mockWorkspace.name)))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    workspaceServer
      .when(request().withMethod("POST").withPath(
        MethodConfigurationApiService.remoteCopyFromMethodRepoConfigPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(Created.intValue)
      )
    workspaceServer
      .when(request().withMethod("POST").withPath(
        MethodConfigurationApiService.remoteCopyToMethodRepoConfigPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(Created.intValue)
      )
  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
  }

  "MethodConfigurationService" - {

    /* Handle passthrough handlers here */

    val localTemplatePath = "/template"
    val localInputsOutputsPath = "/inputsOutputs"

    "when calling the passthrough service" - {
      List(localTemplatePath, localInputsOutputsPath) foreach {
        path =>
          s"POST on $path" - {
            "should not receive a MethodNotAllowed" in {
              Post(path) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
                status shouldNot equal(MethodNotAllowed)
              }
            }
          }

          s"GET, PUT, DELETE on $path" - {
            "should receive a MethodNotAllowed" in {
              List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.DELETE) foreach {
                method =>
                  new RequestBuilder(method)(path) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
                    status should equal(MethodNotAllowed)
                  }
              }
            }
          }
      }

      val localMethodConfigPath = "/workspaces/%s/%s/method_configs/%s/%s".format(
        mockWorkspace.namespace,
        mockWorkspace.name,
        mockWorkspace.namespace,
        mockWorkspace.name)

      s"GET, PUT, POST, and DELETE on $localMethodConfigPath " - {
        "should not receive a MethodNotAllowed" in {
          List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.POST, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(localMethodConfigPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
                status shouldNot equal(MethodNotAllowed)
              }
          }
        }
      }

      s"PATCH on $localMethodConfigPath " - {
        "should receive a MethodNotAllowed" in {
          Patch(localMethodConfigPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }

      val localMethodConfigRenamePath = localMethodConfigPath + "/rename"

      s"POST on $localMethodConfigRenamePath " - {
        "should not receive a MethodNotAllowed" in {
          Post(localMethodConfigRenamePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
            status shouldNot equal(MethodNotAllowed)
          }
        }
      }

      s"GET, PATCH, PUT, DELETE on $localMethodConfigRenamePath " - {
        "should receive a MethodNotAllowed" in {
          List(HttpMethods.GET, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(localMethodConfigRenamePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }

      val localMethodConfigValidatePath = localMethodConfigPath + "/validate"

      s"GET on $localMethodConfigValidatePath " - {
        "should not receive a MethodNotAllowed" in {
          Get(localMethodConfigValidatePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
            status shouldNot equal(MethodNotAllowed)
          }
        }
      }

      s"PUT, POST, PATCH, DELETE on $localMethodConfigValidatePath " - {
        "should receive a MethodNotAllowed" in {
          List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.POST, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(localMethodConfigValidatePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }

    }

    "when copying a method FROM the method repo" - {
      val validCopyFromRepoUrl = s"/workspaces/%s/%s/method_configs/copyFromMethodRepo".format(
        mockWorkspace.namespace,
        mockWorkspace.name
      )
      val configurationCopyFormData = CopyConfigurationIngest(
        configurationNamespace = Option("namespace"),
        configurationName = Option("name"),
        configurationSnapshotId = Option(1),
        destinationNamespace = Option("namespace"),
        destinationName = Option("new-name")
      )

      s"when calling POST on the $validCopyFromRepoUrl path with valid workspace and configuration data" - {
        "Created response is returned" in {
          Post(validCopyFromRepoUrl, configurationCopyFormData) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
            status should equal(Created)
          }
        }
      }

      s"GET, PUT, PATCH, DELETE on $validCopyFromRepoUrl " - {
        "should receive a MethodNotAllowed" in {
          List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(validCopyFromRepoUrl, configurationCopyFormData) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }
    }

    "when copying a method TO the method repo" - {
      val validCopyToRepoUrl = s"/workspaces/%s/%s/method_configs/copyToMethodRepo".format(
        mockWorkspace.namespace,
        mockWorkspace.name
      )
      val configurationPublishFormData = PublishConfigurationIngest(
        configurationNamespace = Option("configNamespace"),
        configurationName = Option("configName"),
        sourceNamespace = Option("sourceNamespace"),
        sourceName = Option("sourceName")
      )

      s"when calling POST on the $validCopyToRepoUrl path with valid workspace and configuration data" - {
        "Created response is returned" in {
          Post(validCopyToRepoUrl, configurationPublishFormData) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
            status should equal(Created)
          }
        }
      }

      s"GET, PUT, PATCH, DELETE on $validCopyToRepoUrl " - {
        "should receive a MethodNotAllowed" in {
          List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(validCopyToRepoUrl, configurationPublishFormData) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodConfigurationRoutes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }
    }

  }

}
