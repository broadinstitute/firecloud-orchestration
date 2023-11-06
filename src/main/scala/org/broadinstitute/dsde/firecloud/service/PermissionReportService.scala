package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{AgoraDAO, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{OrchMethodConfigurationName, PermissionReport, PermissionReportRequest, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.rawls.model.{AccessEntry, WorkspaceACL}
import akka.http.scaladsl.model.StatusCodes._

import scala.concurrent.ExecutionContext


object PermissionReportService {

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new PermissionReportService(userInfo, app.rawlsDAO, app.agoraDAO)

}

class PermissionReportService (protected val argUserInfo: UserInfo, val rawlsDAO: RawlsDAO, val agoraDAO: AgoraDAO) (implicit protected val executionContext: ExecutionContext) extends LazyLogging {

  import PermissionReportService._

  implicit val userInfo: UserInfo = argUserInfo
  
  def getPermissionReport(workspaceNamespace: String, workspaceName: String, reportInput: PermissionReportRequest) = {
    // start the requests to get workspace users and workspace configs in parallel
    val futureWorkspaceACL = rawlsDAO.getWorkspaceACL(workspaceNamespace, workspaceName) recover {
      // User is forbidden from listing ACLs for this workspace, but may still be able to read
      // the configs/methods. Continue with empty workspace ACLs.
      case fcex:FireCloudExceptionWithErrorReport if fcex.errorReport.statusCode.contains(Forbidden) =>
        WorkspaceACL(Map.empty[String, AccessEntry])
      // all other exceptions are considered fatal
    }
    val futureWorkspaceConfigs = rawlsDAO.getAgoraMethodConfigs(workspaceNamespace, workspaceName) map { configs =>
      // filter to just those the user requested
      if (reportInput.configs.isEmpty || reportInput.configs.get.isEmpty) configs
      else configs.filter( x => reportInput.configs.get.contains(OrchMethodConfigurationName(x)))
    }

    for {
      workspaceACL <- futureWorkspaceACL
      workspaceConfigs <- futureWorkspaceConfigs
      methodACLs <- agoraDAO.getMultiEntityPermissions(AgoraEntityType.Workflow,
                      (workspaceConfigs map {config => Method(config.methodRepoMethod)}).distinct.toList)
    } yield {
      // filter the workspace users to what the user requested
      val wsAcl = if (reportInput.users.isEmpty || reportInput.users.get.isEmpty) workspaceACL.acl
        else workspaceACL.acl.filter( x => reportInput.users.get.contains(x._1) )
      val translatedMethodAcl = workspaceConfigs map { config =>
        val methodLookup = Method(config.methodRepoMethod)
        val agoraMethodReference = methodACLs.find(_.entity.toShortString == methodLookup.toShortString)
        agoraMethodReference match {
          case Some(agora) =>
            EntityAccessControl(Some(Method(config.methodRepoMethod, agora.entity.managers, agora.entity.public)),
              OrchMethodConfigurationName(config), agora.acls map AgoraPermissionService.toFireCloudPermission, agora.message)
          case None => EntityAccessControl(None, OrchMethodConfigurationName(config), Seq.empty[FireCloudPermission], Some("referenced method not found."))
        }
      }

      RequestComplete(OK, PermissionReport(wsAcl, translatedMethodAcl))
    }
  }
}
