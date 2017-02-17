package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model._
import org.joda.time.DateTime

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
