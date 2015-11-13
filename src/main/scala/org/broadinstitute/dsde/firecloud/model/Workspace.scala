package org.broadinstitute.dsde.firecloud.model

case class WorkspaceName(
  namespace: Option[String] = None,
  name: Option[String] = None)

case class WorkspaceEntity(
  namespace: Option[String] = None,
  name: Option[String] = None,
  createdDate: Option[String] = None,
  createdBy: Option[String] = None,
  attributes: Option[Map[String, String]] = None)

case class EntityCreateResult(entityType: String, entityName: String, succeeded: Boolean, message: String)

case class EntityCopyDefinition(
  sourceWorkspace: WorkspaceName,
  entityType: String,
  entityNames: Seq[String]
  )

case class EntityCopyWithDestinationDefinition(
  sourceWorkspace: WorkspaceName,
  destinationWorkspace: WorkspaceName,
  entityType: String,
  entityNames: Seq[String]
  )

case class EntityId(entityType: String, entityName: String)
case class EntityDeleteDefinition(recursive: Boolean, entities: Seq[EntityId])

case class MethodConfiguration(
  name: Option[String] = None,
  namespace: Option[String] = None,
  rootEntityType: Option[String] = None,
  workspaceName: Option[Map[String, String]] = None,
  methodRepoMethod: Option[Map[String, String]] = None,
  outputs: Option[Map[String, String]] = None,
  inputs: Option[Map[String, String]] = None,
  prerequisites: Option[Map[String, String]] = None)

case class MethodConfigurationRename(
  name: Option[String] = None,
  namespace: Option[String] = None,
  workspaceName: Option[Map[String, String]] = None)

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
