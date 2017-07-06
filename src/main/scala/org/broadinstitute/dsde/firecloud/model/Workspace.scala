package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.WorkspaceAccessLevels.WorkspaceAccessLevel
import org.broadinstitute.dsde.rawls.model._

case class WorkspaceCreate(
  namespace: String,
  name: String,
  authorizationDomain: Option[ManagedGroupRef] = None,
  attributes: AttributeMap)

object WorkspaceCreate {
  import scala.language.implicitConversions
  implicit def toWorkspaceRequest(wc: WorkspaceCreate): WorkspaceRequest = {
    WorkspaceRequest(wc.namespace, wc.name, wc.authorizationDomain, wc.attributes)
  }

  def toWorkspaceClone(wc: WorkspaceCreate): WorkspaceCreate = {
    new WorkspaceCreate(
      namespace = wc.namespace,
      name = wc.name,
      authorizationDomain = wc.authorizationDomain,
      attributes = wc.attributes + (AttributeName("library","published") -> AttributeBoolean(false)) + (AttributeName("library","discoverableByGroups") -> AttributeValueEmptyList))
  }
}

case class UIWorkspaceResponse(
  accessLevel: Option[String] = None,
  canShare: Option[Boolean] = None,
  catalog: Option[Boolean] = None,
  workspace: Option[Workspace] = None,
  workspaceSubmissionStats: Option[WorkspaceSubmissionStats] = None,
  owners: Option[List[String]] = None)

case class EntityCreateResult(entityType: String, entityName: String, succeeded: Boolean, message: String)

case class EntityCopyWithoutDestinationDefinition(
  sourceWorkspace: WorkspaceName,
  entityType: String,
  entityNames: Seq[String]
  )

case class EntityId(entityType: String, entityName: String)

case class MethodConfigurationId(
  name: Option[String] = None,
  namespace: Option[String] = None,
  workspaceName: Option[WorkspaceName] = None)

case class MethodConfigurationName(
  namespace: String,
  name: String)

object MethodConfigurationName {
  def apply(mcs:MethodConfigurationShort) =
    new MethodConfigurationName(mcs.namespace, mcs.name)
}

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

case class SubmissionRequest(
  methodConfigurationNamespace: Option[String],
  methodConfigurationName: Option[String],
  entityType: Option[String],
  entityName: Option[String],
  expression: Option[String],
  useCallCache: Option[Boolean],
  workflowFailureMode: Option[String])

case class RawlsGroupMemberList(
  userEmails: Option[Seq[String]] = None,
  subGroupEmails: Option[Seq[String]] = None,
  userSubjectIds: Option[Seq[String]] = None,
  subGroupNames: Option[Seq[String]] = None)

case class WorkspaceStorageCostEstimate(estimate: String)
