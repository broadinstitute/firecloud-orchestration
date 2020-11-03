package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.DsdeHttpDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.rawls.model.WorkspaceName
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext.Implicits.global

object MethodConfigurationService {
  val remoteTemplatePath = FireCloudConfig.Rawls.authPrefix + "/methodconfigs/template"
  val remoteTemplateURL = FireCloudConfig.Rawls.baseUrl + remoteTemplatePath

  val remoteInputsOutputsPath = FireCloudConfig.Rawls.authPrefix + "/methodconfigs/inputsOutputs"
  val remoteInputsOutputsURL = FireCloudConfig.Rawls.baseUrl + remoteInputsOutputsPath

  val remoteCopyFromMethodRepoConfigPath = FireCloudConfig.Rawls.authPrefix + "/methodconfigs/copyFromMethodRepo"
  val remoteCopyFromMethodRepoConfigUrl = FireCloudConfig.Rawls.baseUrl + remoteCopyFromMethodRepoConfigPath

  val remoteCopyToMethodRepoConfigPath = FireCloudConfig.Rawls.authPrefix + "/methodconfigs/copyToMethodRepo"
  val remoteCopyToMethodRepoConfigUrl = FireCloudConfig.Rawls.baseUrl + remoteCopyToMethodRepoConfigPath

  def remoteMethodConfigPath(workspaceNamespace:String, workspaceName:String, configNamespace:String, configName:String) =
    FireCloudConfig.Rawls.authPrefix + "/workspaces/%s/%s/methodconfigs/%s/%s".format(workspaceNamespace, workspaceName, configNamespace, configName)
  def remoteMethodConfigUrl(workspaceNamespace:String, workspaceName:String, configNamespace:String, configName:String) =
    FireCloudConfig.Rawls.baseUrl + remoteMethodConfigPath(workspaceNamespace, workspaceName, configNamespace, configName)

  def remoteMethodConfigRenamePath(workspaceNamespace:String, workspaceName:String, configNamespace:String, configName:String) =
    FireCloudConfig.Rawls.authPrefix + "/workspaces/%s/%s/methodconfigs/%s/%s/rename".format(workspaceNamespace, workspaceName, configNamespace, configName)
  def remoteMethodConfigRenameUrl(workspaceNamespace:String, workspaceName:String, configNamespace:String, configName:String) =
    FireCloudConfig.Rawls.baseUrl + remoteMethodConfigRenamePath(workspaceNamespace, workspaceName, configNamespace, configName)

  def remoteMethodConfigValidatePath(workspaceNamespace:String, workspaceName:String, configNamespace:String, configName:String) =
    FireCloudConfig.Rawls.authPrefix + "/workspaces/%s/%s/methodconfigs/%s/%s/validate".format(workspaceNamespace, workspaceName, configNamespace, configName)
  def remoteMethodConfigValidateUrl(workspaceNamespace:String, workspaceName:String, configNamespace:String, configName:String) =
    FireCloudConfig.Rawls.baseUrl + remoteMethodConfigValidatePath(workspaceNamespace, workspaceName, configNamespace, configName)

}

trait MethodConfigurationService extends FireCloudDirectives with SprayJsonSupport with StandardUserInfoDirectives {

  private final val ApiPrefix = "workspaces"
  lazy val log = LoggerFactory.getLogger(getClass)

  val methodConfigurationRoutes: Route = requireUserInfo() { userInfo =>
    path("template") {
      passthrough(MethodConfigurationService.remoteTemplateURL, HttpMethods.POST)
    } ~
      path("inputsOutputs") {
        passthrough(MethodConfigurationService.remoteInputsOutputsURL, HttpMethods.POST)
      } ~
      pathPrefix(ApiPrefix) {
        pathPrefix(Segment / Segment / "method_configs") { (workspaceNamespace, workspaceName) =>
          path("copyFromMethodRepo") {
            post {
              entity(as[CopyConfigurationIngest]) { ingest =>
                val copyMethodConfig = new MethodConfigurationCopy(
                  methodRepoName = ingest.configurationName,
                  methodRepoNamespace = ingest.configurationNamespace,
                  methodRepoSnapshotId = ingest.configurationSnapshotId,
                  destination = Option(MethodConfigurationId(
                    name = ingest.destinationName,
                    namespace = ingest.destinationNamespace,
                    workspaceName = Option(WorkspaceName(
                      namespace = workspaceNamespace,
                      name = workspaceName)))))
                val extReq = Post(MethodConfigurationService.remoteCopyFromMethodRepoConfigUrl, copyMethodConfig)

                complete { executeRequestRaw(userInfo.accessToken)(extReq) }
              }
            }
          } ~ path("copyToMethodRepo") {
            post {
              entity(as[PublishConfigurationIngest]) { ingest =>
                val copyMethodConfig = new MethodConfigurationPublish(
                  methodRepoName = ingest.configurationName,
                  methodRepoNamespace = ingest.configurationNamespace,
                  source = Option(MethodConfigurationId(
                    name = ingest.sourceName,
                    namespace = ingest.sourceNamespace,
                    workspaceName = Option(WorkspaceName(
                      namespace = workspaceNamespace,
                      name = workspaceName)))))
                val extReq = Post(MethodConfigurationService.remoteCopyToMethodRepoConfigUrl, copyMethodConfig)

                complete { executeRequestRaw(userInfo.accessToken)(extReq) }
              }
            }
          } ~ pathPrefix(Segment / Segment) { (configNamespace, configName) =>
            pathEnd {
              passthrough(
                encodeUri(MethodConfigurationService.remoteMethodConfigUrl(workspaceNamespace, workspaceName, configNamespace, configName)),
                HttpMethods.GET, HttpMethods.PUT, HttpMethods.POST, HttpMethods.DELETE)
            } ~
              path("rename") {
                passthrough(encodeUri(MethodConfigurationService.remoteMethodConfigRenameUrl(workspaceNamespace, workspaceName, configNamespace, configName)),
                  HttpMethods.POST)
              } ~
              path("validate") {
                passthrough(encodeUri(MethodConfigurationService.remoteMethodConfigValidateUrl(workspaceNamespace, workspaceName, configNamespace, configName)),
                  HttpMethods.GET)
              }
          }
        }
      }
  }

}
