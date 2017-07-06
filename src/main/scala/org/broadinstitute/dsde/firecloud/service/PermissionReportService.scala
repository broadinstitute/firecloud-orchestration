package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.firecloud.dataaccess.{AgoraDAO, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.MethodRepository._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{MethodConfigurationName, PermissionReport, PermissionReportRequest, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport._

import scala.concurrent.ExecutionContext


object PermissionReportService {
  case class GetPermissionReport(workspaceNamespace: String, workspaceName: String, reportInput: PermissionReportRequest)

  def props(permissionReportServiceConstructor: UserInfo => PermissionReportService, userInfo: UserInfo): Props = {
    Props(permissionReportServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new PermissionReportService(userInfo, app.rawlsDAO, app.agoraDAO)
}

class PermissionReportService (protected val argUserInfo: UserInfo, val rawlsDAO: RawlsDAO, val agoraDAO: AgoraDAO) (implicit protected val executionContext: ExecutionContext) extends Actor
  with LazyLogging {

  import PermissionReportService._

  implicit val system = context.system
  implicit val userInfo = argUserInfo

  override def receive: Receive = {
    case GetPermissionReport(workspaceNamespace: String, workspaceName: String, reportInput: PermissionReportRequest) =>
      getPermissionReport(workspaceNamespace, workspaceName, reportInput) pipeTo sender
  }

  def getPermissionReport(workspaceNamespace: String, workspaceName: String, reportInput: PermissionReportRequest) = {
    // start the requests to get workspace users and workspace configs in parallel
    val futureWorkspaceACL = rawlsDAO.getWorkspaceACL(workspaceNamespace, workspaceName)
    val futureWorkspaceConfigs = rawlsDAO.getMethodConfigs(workspaceNamespace, workspaceName) map { configs =>
      // filter to just those the user requested
      if (reportInput.configs.isEmpty || reportInput.configs.get.isEmpty) configs
      else configs.filter( x => reportInput.configs.get.contains(MethodConfigurationName(x.name, x.namespace)))
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
        val agoraMethodReference = methodACLs.find(_.entity == methodLookup)
        agoraMethodReference match {
          case Some(agora) => EntityAccessControl(Some(config.methodRepoMethod), MethodConfigurationName(config), agora.acls map AgoraPermissionHandler.toFireCloudPermission, agora.message)
          case None => EntityAccessControl(None, MethodConfigurationName(config), Seq.empty[FireCloudPermission], Some("referenced method not found."))
        }
      }

      RequestComplete(OK, PermissionReport(wsAcl, translatedMethodAcl))
    }
  }
}
