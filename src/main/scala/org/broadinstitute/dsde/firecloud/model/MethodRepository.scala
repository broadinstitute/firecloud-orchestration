package org.broadinstitute.dsde.firecloud.model

import com.wordnik.swagger.annotations.{ApiModelProperty, ApiModel}

import scala.annotation.meta.field

object MethodRepository {

  @ApiModel(value = "Configuration")
  case class Configuration(
                            @(ApiModelProperty@field)(dataType = "string", value = "Namespace")
                            namespace: Option[String] = None,
                            @(ApiModelProperty@field)(dataType = "string", value = "Name ")
                            name: Option[String] = None,
                            @(ApiModelProperty@field)(dataType = "string", value = "Snapshot Id")
                            snapshotId: Option[Int] = None,
                            @(ApiModelProperty@field)(dataType = "string", value = "Synopsis")
                            synopsis: Option[String] = None,
                            @(ApiModelProperty@field)(dataType = "string", value = "Documentation")
                            documentation: Option[String] = None,
                            @(ApiModelProperty@field)(dataType = "string", value = "Owner")
                            owner: Option[String] = None,
                            @(ApiModelProperty@field)(dataType = "string", value = "Payload")
                            payload: Option[String] = None,
                            @(ApiModelProperty@field)(dataType = "string", value = "Excluded Field")
                            excludedField: Option[String] = None,
                            @(ApiModelProperty@field)(dataType = "string", value = "Included Field")
                            includedField: Option[String] = None)

  @ApiModel(value = "Method")
  case class Method(
                     @(ApiModelProperty@field)(dataType = "string", value = "Namespace")
                     namespace: Option[String] = None,
                     @(ApiModelProperty@field)(dataType = "string", value = "Name")
                     name: Option[String] = None,
                     @(ApiModelProperty@field)(dataType = "string", value = "Snapshot Id")
                     snapshotId: Option[Int] = None,
                     @(ApiModelProperty@field)(dataType = "string", value = "Synopsis")
                     synopsis: Option[String] = None,
                     @(ApiModelProperty@field)(dataType = "string", value = "Owner")
                     owner: Option[String] = None,
                     @(ApiModelProperty@field)(dataType = "string", value = "Creation Date")
                     createDate: Option[String] = None,
                     @(ApiModelProperty@field)(dataType = "string", value = "Payload")
                     url: Option[String] = None,
                     @(ApiModelProperty@field)(dataType = "string", value = "The type of the entity (Task, Workflow, Configuration)")
                     entityType: Option[String] = None)

}
