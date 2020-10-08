package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{CopyConfigurationIngest, PublishConfigurationIngest}
import org.broadinstitute.dsde.rawls.model.WorkspaceDetails
import org.joda.time.DateTime
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes._
import spray.httpx.SprayJsonSupport._

class MethodConfigurationServiceSpec extends ServiceSpec with MethodConfigurationService {

  def actorRefFactory = system

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
    Map(), //attributes
    false, //locked
    Set.empty
  )

  override def beforeAll(): Unit = {
    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)
    List(MethodConfigurationService.remoteTemplatePath, MethodConfigurationService.remoteInputsOutputsPath) map {
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
            MethodConfigurationService.remoteMethodConfigPath(
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
        MethodConfigurationService.remoteMethodConfigRenamePath(
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
        MethodConfigurationService.remoteMethodConfigValidatePath(
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
        MethodConfigurationService.remoteCopyFromMethodRepoConfigPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(Created.intValue)
      )
    workspaceServer
      .when(request().withMethod("POST").withPath(
        MethodConfigurationService.remoteCopyToMethodRepoConfigPath))
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
      List(localTemplatePath, localInputsOutputsPath) map {
        path =>
          s"POST on $path" - {
            "should not receive a MethodNotAllowed" in {
              Post(path) ~> sealRoute(routes) ~> check {
                status shouldNot equal(MethodNotAllowed)
              }
            }
          }

          s"GET, PUT, DELETE on $path" - {
            "should receive a MethodNotAllowed" in {
              List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.DELETE) map {
                method =>
                  new RequestBuilder(method)(path) ~> sealRoute(routes) ~> check {
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
              new RequestBuilder(method)(localMethodConfigPath) ~> sealRoute(routes) ~> check {
                status shouldNot equal(MethodNotAllowed)
              }
          }
        }
      }

      s"PATCH on $localMethodConfigPath " - {
        "should receive a MethodNotAllowed" in {
          Patch(localMethodConfigPath) ~> sealRoute(routes) ~> check {
            status should equal(MethodNotAllowed)
          }
        }
      }

      val localMethodConfigRenamePath = localMethodConfigPath + "/rename"

      s"POST on $localMethodConfigRenamePath " - {
        "should not receive a MethodNotAllowed" in {
          Post(localMethodConfigRenamePath) ~> sealRoute(routes) ~> check {
            status shouldNot equal(MethodNotAllowed)
          }
        }
      }

      s"GET, PATCH, PUT, DELETE on $localMethodConfigRenamePath " - {
        "should receive a MethodNotAllowed" in {
          List(HttpMethods.GET, HttpMethods.PATCH, HttpMethods.PUT, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(localMethodConfigRenamePath) ~> sealRoute(routes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }

      val localMethodConfigValidatePath = localMethodConfigPath + "/validate"

      s"GET on $localMethodConfigValidatePath " - {
        "should not receive a MethodNotAllowed" in {
          Get(localMethodConfigValidatePath) ~> sealRoute(routes) ~> check {
            status shouldNot equal(MethodNotAllowed)
          }
        }
      }

      s"PUT, POST, PATCH, DELETE on $localMethodConfigValidatePath " - {
        "should receive a MethodNotAllowed" in {
          List(HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.POST, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(localMethodConfigValidatePath) ~> sealRoute(routes) ~> check {
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
          Post(validCopyFromRepoUrl, configurationCopyFormData) ~> sealRoute(routes) ~> check {
            status should equal(Created)
          }
        }
      }

      s"GET, PUT, PATCH, DELETE on $validCopyFromRepoUrl " - {
        "should receive a MethodNotAllowed" in {
          List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(validCopyFromRepoUrl, configurationCopyFormData) ~> sealRoute(routes) ~> check {
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
          Post(validCopyToRepoUrl, configurationPublishFormData) ~> sealRoute(routes) ~> check {
            status should equal(Created)
          }
        }
      }

      s"GET, PUT, PATCH, DELETE on $validCopyToRepoUrl " - {
        "should receive a MethodNotAllowed" in {
          List(HttpMethods.GET, HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE) map {
            method =>
              new RequestBuilder(method)(validCopyToRepoUrl, configurationPublishFormData) ~> sealRoute(routes) ~> check {
                status should equal(MethodNotAllowed)
              }
          }
        }
      }
    }

  }

}
