package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.WorkspaceAccessLevels.WorkspaceAccessLevel
import org.broadinstitute.dsde.rawls.model._

case class WorkspaceCreate(
  namespace: String,
  name: String,
  attributes: AttributeMap,
  isProtected: Option[Boolean] = Some(false))

object WorkspaceCreate {
  import scala.language.implicitConversions
  implicit def toWorkspaceRequest(wc: WorkspaceCreate): WorkspaceRequest = {
    val realm = if (wc.isProtected.getOrElse(false))
      Some(RawlsRealmRef(RawlsGroupName(FireCloudConfig.Nih.rawlsGroupName)))
    else
        None
    WorkspaceRequest(wc.namespace, wc.name, realm, wc.attributes)
  }

  def isProtected(ws: Workspace): Boolean = {
    ws.realm == Some(RawlsRealmRef(RawlsGroupName(FireCloudConfig.Nih.rawlsGroupName)))
  }

  def toWorkspaceClone(wc: WorkspaceCreate): WorkspaceCreate = {
    new WorkspaceCreate(
      namespace = wc.namespace,
      name = wc.name,
      attributes = wc.attributes + (AttributeName("library","published") -> AttributeBoolean(false)),
      isProtected = wc.isProtected)
  }

}

case class UIWorkspaceResponse(
  accessLevel: Option[String] = None,
  canShare: Option[Boolean] = None,
  workspace: Option[UIWorkspace] = None,
  workspaceSubmissionStats: Option[WorkspaceSubmissionStats] = None,
  owners: Option[List[String]] = None) {
  def this(wr: WorkspaceResponse) =
    this(Option(wr.accessLevel.toString), Option(wr.canShare), Option(new UIWorkspace(wr.workspace)), Option(wr.workspaceSubmissionStats), Option(wr.owners.toList))
  def this(wlr: WorkspaceListResponse) =
    this(Option(wlr.accessLevel.toString), None, Option(new UIWorkspace(wlr.workspace)), Option(wlr.workspaceSubmissionStats), Option(wlr.owners.toList))
}

/** A Firecloud UI focused result object that performs extra translation on the result from Rawls, specifically
  * interpreting the NIH realm as a binary "protected" flag.
  *
  * Note: Depending on the direction that firecloud-orchestration takes in the future, we may keep this here or move
  * this logic into firecloud-ui as part of https://broadinstitute.atlassian.net/browse/GAWB-1674. See discussion in
  * https://github.com/broadinstitute/firecloud-orchestration/pull/388.
  */
case class UIWorkspace(
  workspaceId: String,
  namespace: String,
  name: String,
  isLocked: Option[Boolean] = None,
  createdBy: String,
  createdDate: String,
  lastModified: Option[String] = None,
  attributes: AttributeMap,
  bucketName: String,
  accessLevels: Map[WorkspaceAccessLevel, RawlsGroupRef],
  realm: Option[RawlsRealmRef],
  isProtected: Boolean) {
  def this(w: Workspace) =
    this(w.workspaceId, w.namespace, w.name, Option(w.isLocked), w.createdBy, w.createdDate.toString,
      Option(w.lastModified.toString), w.attributes, w.bucketName, w.accessLevels, w.realm,
      w.realm.exists(_.realmName.value == FireCloudConfig.Nih.rawlsGroupName))
}

case class EntityCreateResult(entityType: String, entityName: String, succeeded: Boolean, message: String)

case class EntityCopyWithoutDestinationDefinition(
  sourceWorkspace: WorkspaceName,
  entityType: String,
  entityNames: Seq[String]
  )

case class EntityId(entityType: String, entityName: String)
case class EntityDeleteDefinition(recursive: Boolean, entities: Seq[EntityId])

case class MethodConfigurationId(
  name: Option[String] = None,
  namespace: Option[String] = None,
  workspaceName: Option[WorkspaceName] = None)

case class MethodConfigurationCopy(
  methodRepoNamespace: Option[String] = None,
  methodRepoName: Option[String] = None,
  methodRepoSnapshotId: Option[Int] = None,
  destination: Option[MethodConfigurationId] = None)

case class MethodConfigurationPublish(
  methodRepoNamespace: Option[String] = None,
  methodRepoName: Option[String] = None,
  source: Option[MethodConfigurationId] = None)

case class CopyConfigurationIngest(
  configurationNamespace: Option[String],
  configurationName: Option[String],
  configurationSnapshotId: Option[Int],
  destinationNamespace: Option[String],
  destinationName: Option[String])

case class PublishConfigurationIngest(
  configurationNamespace: Option[String],
  configurationName: Option[String],
  sourceNamespace: Option[String],
  sourceName: Option[String])

case class SubmissionIngest(
  methodConfigurationNamespace: Option[String],
  methodConfigurationName: Option[String],
  entityType: Option[String],
  entityName: Option[String],
  expression: Option[String])

case class RawlsGroupMemberList(
  userEmails: Option[Seq[String]] = None,
  subGroupEmails: Option[Seq[String]] = None,
  userSubjectIds: Option[Seq[String]] = None,
  subGroupNames: Option[Seq[String]] = None)

case class WorkspaceStorageCostEstimate(estimate: String)
