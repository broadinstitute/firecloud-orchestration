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
            passthrough(FireCloudConfig.Rawls.getMethodConfigUrl.
              format(workspaceNamespace, workspaceName, configNamespace, configName), "get", "delete") ~
            passthrough(FireCloudConfig.Rawls.updateMethodConfigurationUrl.
              format(workspaceNamespace, workspaceName, configNamespace, configName), "put")
          } ~
          path("rename") {
            passthrough(FireCloudConfig.Rawls.renameMethodConfigurationUrl.
              format(workspaceNamespace, workspaceName, configNamespace, configName), "post")
          }
        }
      }
    }
}
