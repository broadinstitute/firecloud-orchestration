package org.broadinstitute.dsde.firecloud.dataaccess

import akka.http.scaladsl.model.HttpResponse
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.AgoraConfigurationShort
import org.broadinstitute.dsde.firecloud.model.Project.ProjectRoles.ProjectRole
import org.broadinstitute.dsde.firecloud.model.Project.{RawlsBillingProjectMember, RawlsBillingProjectMembership}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectiveUtils
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.workbench.util.health
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem
import org.joda.time.DateTime

import scala.concurrent.Future

/**
  * Created by davidan on 9/23/16.
  */

object RawlsDAO {
  lazy val serviceName = health.Subsystems.Rawls

  def groupUrl(group: String): String = authedUrl(s"/user/group/$group")
  private def authedUrl(path: String) = pathToUrl(FireCloudConfig.Rawls.authPrefix + path)
  private def pathToUrl(path: String) = FireCloudConfig.Rawls.baseUrl + path
}

trait RawlsDAO extends LazyLogging with ReportsSubsystemStatus {

  def encodeUri(path: String): String = FireCloudDirectiveUtils.encodeUri(path)

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource(RawlsDAO.serviceName.value)

  lazy val rawlsWorkspacesRoot = FireCloudConfig.Rawls.workspacesUrl
  lazy val rawlsAdminUrl = FireCloudConfig.Rawls.authUrl + "/user/role/admin"
  lazy val rawlsCuratorUrl = FireCloudConfig.Rawls.authUrl + "/user/role/curator"
  lazy val rawlsWorkpacesUrl = FireCloudConfig.Rawls.workspacesUrl
  lazy val rawlsAdminWorkspaces = FireCloudConfig.Rawls.authUrl + "/admin/workspaces?attributeName=library:published&valueBoolean=true"
  def rawlsWorkspaceACLUrl(workspaceNamespace: String, workspaceName: String): String = encodeUri(FireCloudConfig.Rawls.workspacesUrl + s"/$workspaceNamespace/$workspaceName/acl")
  lazy val rawlsWorkspaceACLQuerystring = "?inviteUsersNotFound=%s"
  def rawlsWorkspaceMethodConfigsUrl(workspaceNamespace: String, workspaceName: String): String = encodeUri(FireCloudConfig.Rawls.workspacesUrl + s"/$workspaceNamespace/$workspaceName/methodconfigs")
  def rawlsBucketUsageUrl(workspaceNamespace: String, workspaceName: String): String = encodeUri(FireCloudConfig.Rawls.workspacesUrl + s"/$workspaceNamespace/$workspaceName/bucketUsage")

  def rawlsEntitiesOfTypeUrl(workspaceNamespace: String, workspaceName: String, entityType: String): String = encodeUri(FireCloudConfig.Rawls.workspacesUrl + s"/$workspaceNamespace/$workspaceName/entities/$entityType")

  def isAdmin(userInfo: UserInfo): Future[Boolean]

  def isLibraryCurator(userInfo: UserInfo): Future[Boolean]

  def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse]

  def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]]

  def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse]

  def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails]

  def updateLibraryAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails]

  // you must be an admin to execute this method
  def getAllLibraryPublishedWorkspaces(implicit userToken: WithAccessToken): Future[Seq[WorkspaceDetails]]

  def getWorkspaceACL(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceACL]

  def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate], inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList]

  def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userToken: UserInfo): Future[Seq[Entity]]

  def queryEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery)(implicit userToken: UserInfo): Future[EntityQueryResponse]

  def getEntityTypes(workspaceNamespace: String, workspaceName: String)(implicit userToken: UserInfo): Future[Map[String, EntityTypeMetadata]]

  def getCatalog(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[Seq[WorkspaceCatalog]]

  def patchCatalog(workspaceNamespace: String, workspaceName: String, updates: Seq[WorkspaceCatalog])(implicit userToken: WithAccessToken): Future[WorkspaceCatalogUpdateResponseList]

  def getAgoraMethodConfigs(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[Seq[AgoraConfigurationShort]]

  def deleteWorkspace(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[Option[String]]

  def cloneWorkspace(workspaceNamespace: String, workspaceName: String, cloneRequest: WorkspaceRequest)(implicit userToken: WithAccessToken): Future[WorkspaceDetails]

  def getProjects(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMembership]]

  def getProjectMembers(projectId: String)(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMember]]

  def addUserToBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean]

  def removeUserFromBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean]

  def batchUpsertEntities(workspaceNamespace: String, workspaceName: String, entityType: String, upserts: Seq[EntityUpdateDefinition])(implicit userToken: UserInfo): Future[HttpResponse]

  def batchUpdateEntities(workspaceNamespace: String, workspaceName: String, entityType: String, updates: Seq[EntityUpdateDefinition])(implicit userToken: UserInfo): Future[HttpResponse]

  override def serviceName:Subsystem = RawlsDAO.serviceName

}
