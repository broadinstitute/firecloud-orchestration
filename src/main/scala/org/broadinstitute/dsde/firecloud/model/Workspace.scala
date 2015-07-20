package org.broadinstitute.dsde.firecloud.model

import com.wordnik.swagger.annotations.{ApiModel, ApiModelProperty}

import scala.annotation.meta.field

@ApiModel(value = "Workspace Ingest")
case class WorkspaceIngest  (
                              @(ApiModelProperty@field)(required = true, value = "The namespace the workspace belongs to")
                              namespace: Option[String] = None,
                              @(ApiModelProperty@field)(required = true, value = "The name of the workspace")
                              name: Option[String] = None)

// TODO: Revisit this if the rawls service removes createdDate and createdBy from their API.
@ApiModel(value = "Workspace Entity")
case class WorkspaceEntity  (
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