package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.AgoraConfigurationShort
import org.broadinstitute.dsde.rawls.model._
import org.joda.time.DateTime

case class UIWorkspaceResponse(
  accessLevel: Option[String] = None,
  canShare: Option[Boolean] = None,
  catalog: Option[Boolean] = None,
  workspace: Option[WorkspaceDetails] = None,
  workspaceSubmissionStats: Option[WorkspaceSubmissionStats] = None,
  owners: Option[List[String]] = None)

case class EntityCreateResult(entityType: String, entityName: String, succeeded: Boolean, message: String)

case class EntityCopyWithoutDestinationDefinition(
  sourceWorkspace: WorkspaceName,
  entityType: String,
  entityNames: Seq[String]
  )

case class EntityId(entityType: String, entityName: String)

case class BagitImportRequest(bagitURL: String, format: String)

// legacy class specific to PFB import; prefer AsyncImportRequest instead
case class PFBImportRequest(url: String)

// additional import options
case class ImportOptions(tdrSyncPermissions: Option[Boolean] = None, isUpsert: Option[Boolean] = None)
// the request payload sent by users to Orchestration for async PFB and TDR snapshot imports
case class AsyncImportRequest(url: String, filetype: String, options: Option[ImportOptions] = None)

// the response payload received by users from Orchestration for async PFB/TSV/TDR snapshot imports
case class AsyncImportResponse(url: String,
                               jobId: String,
                               workspace: WorkspaceName)

// the request payload sent by Orchestration to Import Service
case class ImportServiceRequest(
  path: String,
  filetype: String,
  isUpsert: Boolean,
  options: Option[ImportOptions])
// the response payload received by Orchestration from Import Service
case class ImportServiceResponse(
  jobId: String,
  status: String,
  message: Option[String])

case class ImportServiceListResponse(
                                  jobId: String,
                                  status: String,
                                  filetype: String,
                                  message: Option[String])

case class MethodConfigurationId(
  name: Option[String] = None,
  namespace: Option[String] = None,
  workspaceName: Option[WorkspaceName] = None)

case class OrchMethodConfigurationName(
  namespace: String,
  name: String)

object OrchMethodConfigurationName {
  def apply(mcs:MethodConfigurationShort) =
    new OrchMethodConfigurationName(mcs.namespace, mcs.name)
  def apply(mcs: AgoraConfigurationShort) =
    new OrchMethodConfigurationName(mcs.namespace, mcs.name)
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

case class OrchSubmissionRequest(
  methodConfigurationNamespace: Option[String],
  methodConfigurationName: Option[String],
  entityType: Option[String],
  entityName: Option[String],
  expression: Option[String],
  useCallCache: Option[Boolean],
  deleteIntermediateOutputFiles: Option[Boolean],
  useReferenceDisks: Option[Boolean],
  memoryRetryMultiplier: Option[Double],
  userComment: Option[String],
  workflowFailureMode: Option[String])

case class RawlsGroupMemberList(
  userEmails: Option[Seq[String]] = None,
  subGroupEmails: Option[Seq[String]] = None,
  userSubjectIds: Option[Seq[String]] = None,
  subGroupNames: Option[Seq[String]] = None)

case class WorkspaceStorageCostEstimate(estimate: String, lastUpdated: Option[DateTime])
