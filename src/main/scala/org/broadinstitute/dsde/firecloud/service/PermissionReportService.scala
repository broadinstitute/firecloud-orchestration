package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.firecloud.dataaccess.{AgoraDAO, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.MethodRepository._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{MethodConfigurationId, PermissionReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport._

import scala.concurrent.ExecutionContext


object PermissionReportService {
  case class GetPermissionReport(workspaceNamespace: String, workspaceName: String)

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
    case GetPermissionReport(workspaceNamespace: String, workspaceName: String) =>
      getPermissionReport(workspaceNamespace, workspaceName) pipeTo sender
  }

  def getPermissionReport(workspaceNamespace: String, workspaceName: String) = {
    // start these in parallel
    val futureWorkspaceACL = rawlsDAO.getWorkspaceACL(workspaceNamespace, workspaceName)
    val futureWorkspaceConfigs = rawlsDAO.getMethodConfigs(workspaceNamespace, workspaceName)

    for {
      workspaceACL <- futureWorkspaceACL
      workspaceConfigs <- futureWorkspaceConfigs
      methodACLs <- agoraDAO.getMultiEntityPermissions(AgoraEntityType.Workflow,
                      (workspaceConfigs map {config => Method(config.methodRepoMethod)}).distinct.toList)
    } yield {
      val wsAcl = workspaceACL.acl
      val translatedMethodAcl = workspaceConfigs map { config =>
        val methodLookup = Method(config.methodRepoMethod)
        val agoraMethodReference = methodACLs.find(_.entity == methodLookup)
        agoraMethodReference match {
          case Some(agora) => EntityAccessControl(Some(config.methodRepoMethod), MethodConfigurationId(config), agora.acls map AgoraPermissionHandler.toFireCloudPermission, agora.message)
          case None => EntityAccessControl(None, MethodConfigurationId(config), Seq.empty[FireCloudPermission], Some("referenced method not found."))
        }
      }
      RequestComplete(OK, PermissionReport(wsAcl, translatedMethodAcl))
    }
  }
}
