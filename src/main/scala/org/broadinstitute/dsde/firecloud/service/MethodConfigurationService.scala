package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.httpx.SprayJsonSupport._
import spray.routing._

class MethodConfigurationServiceActor extends Actor with MethodConfigurationService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait MethodConfigurationService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private final val ApiPrefix = "workspaces"
  lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    pathPrefix(ApiPrefix) {
      pathPrefix(Segment / Segment / "method_configs") { (workspaceNamespace, workspaceName) =>
        path("copyFromMethodRepo") {
          post {
            entity(as[CopyConfigurationIngest]) { ingest => requestContext =>
              val copyMethodConfig = new MethodConfigurationCopy(
                methodRepoName = ingest.configurationName,
                methodRepoNamespace = ingest.configurationNamespace,
                methodRepoSnapshotId = ingest.configurationSnapshotId,
                destination = Option(Destination(
                  name = ingest.destinationName,
                  namespace = ingest.destinationNamespace,
                  workspaceName = Option(WorkspaceName(
                    namespace = Option(workspaceNamespace),
                    name = Option(workspaceName))))))
              val extReq = Post(FireCloudConfig.Rawls.copyFromMethodRepoConfigUrl, copyMethodConfig)
              externalHttpPerRequest(requestContext, extReq)
            }
          }
        } ~ pathPrefix(Segment / Segment) { (configNamespace, configName) =>
          pathEnd {
            get { requestContext =>
              val extReq = Get(FireCloudConfig.Rawls.getMethodConfigUrl.
                format(workspaceNamespace, workspaceName, configNamespace, configName))
              externalHttpPerRequest(requestContext, extReq)
            } ~
            put {
              entity(as[MethodConfiguration]) { methodConfig =>
                requestContext =>
                  val endpointUrl = FireCloudConfig.Rawls.updateMethodConfigurationUrl.
                    format(workspaceNamespace, workspaceName, configNamespace, configName)
                  val extReq = Put(endpointUrl, methodConfig)
                  externalHttpPerRequest(requestContext, extReq)
              }
            } ~
            delete { requestContext =>
              val extReq = Delete(FireCloudConfig.Rawls.getMethodConfigUrl.
                format(workspaceNamespace, workspaceName, configNamespace, configName))
              externalHttpPerRequest(requestContext, extReq)
            }
          } ~ path("rename") {
            post {
              entity(as[MethodConfigurationRename]) { methodConfigRename =>
                requestContext =>
                  val endpointUrl = FireCloudConfig.Rawls.renameMethodConfigurationUrl.
                    format(workspaceNamespace, workspaceName, configNamespace, configName)
                  val extReq = Post(endpointUrl, methodConfigRename)
                  externalHttpPerRequest(requestContext, extReq)
              }
            }
          }
        }
      }
    }
}
