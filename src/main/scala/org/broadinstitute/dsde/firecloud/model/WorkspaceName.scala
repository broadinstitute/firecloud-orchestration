package org.broadinstitute.dsde.firecloud.model

import com.wordnik.swagger.annotations.{ApiModel, ApiModelProperty}

import scala.annotation.meta.field

@ApiModel(value = "Workspace Name")
case class WorkspaceName(
  @(ApiModelProperty@field)(required = true, value = "The namespace the workspace belongs to")
  namespace: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "The name of the workspace")
  name: Option[String] = None)

// TODO: Revisit this if the rawls service removes createdDate and createdBy from their API.
@ApiModel(value = "Workspace Entity")
case class WorkspaceEntity(
  @(ApiModelProperty@field)(required = true, value = "The namespace the workspace belongs to")
  namespace: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "The name of the workspace")
  name: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "The date the workspace was created in yyyy-MM-dd'T'HH:mm:ssZZ format")
  createdDate: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "The user who created the workspace")
  createdBy: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "The attributes of the workspace")
  attributes: Option[Map[String, String]] = None)

case class EntityCreateResult(entityType: String, entityName: String, succeeded: Boolean, message: String)

// TODO: This is a stub case class until we know what we're returning from the batch entity create endpoint.
@ApiModel(value = "method configuration entity")
case class MethodConfiguration(
  @(ApiModelProperty@field)(required = true, value = "method configuration name")
  name: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "method configuration namespace")
  namespace: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "root entity type")
  rootEntityType: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "map with corresponding workspace-related information : name  and namespace ")
  workspaceName: Option[Map[String, String]] = None,
  @(ApiModelProperty@field)(required = true, value = "map with corresponding method-related information")
  methodStoreMethod: Option[Map[String, String]] = None,
  @(ApiModelProperty@field)(required = true, value = "map with corresponding method-store-related information")
  methodStoreConfig: Option[Map[String, String]] = None,
  @(ApiModelProperty@field)(required = true, value = "map with outputs information")
  outputs: Option[Map[String, String]] = None,
  @(ApiModelProperty@field)(required = true, value = "map with inputs information")
  inputs: Option[Map[String, String]] = None,
  @(ApiModelProperty@field)(required = true, value = "PREREQUISITES:TODO PUT MORE PRECISE INFORMATION AND DETAIL HERE")
  prerequisites: Option[Map[String, String]] = None)


@ApiModel(value = "Method Repository Configuration Copy Destination")
case class Destination(
  @(ApiModelProperty@field)(required = true, value = "method configuration destination name")
  name: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "method configuration destination namespace")
  namespace: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "method configuration destination workspace")
  workspaceName: Option[WorkspaceName] = None)

@ApiModel(value = "Method Repository Configuration Copy")
case class MethodConfigurationCopy(
  @(ApiModelProperty@field)(required = true, value = "method configuration namespace")
  methodRepoNamespace: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "method configuration name")
  methodRepoName: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "method configuration snapshot id")
  methodRepoSnapshotId: Option[String] = None,
  @(ApiModelProperty@field)(required = true, value = "method configuration destination")
  destination: Option[Destination] = None)
