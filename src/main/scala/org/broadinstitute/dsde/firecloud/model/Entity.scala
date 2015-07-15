package org.broadinstitute.dsde.firecloud.model

/**
 * Created by mbemis on 7/15/15.
 */

import com.wordnik.swagger.annotations.{ApiModel, ApiModelProperty}

import scala.annotation.meta.field

@ApiModel(value = "Entity")
case class Entity  (
                              @(ApiModelProperty@field)(required = true, value = "The namespace the entity belongs to")
                              wsNamespace: Option[String] = None,
                              @(ApiModelProperty@field)(required = true, value = "The name of the workspace the entity belongs to")
                              wsName: Option[String] = None,
                              @(ApiModelProperty@field)(required = true, value = "The type of the entity")
                              entityType: Option[String] = None,
                              @(ApiModelProperty@field)(required = true, value = "The name of the entity")
                              entityName: Option[String] = None,
                              @(ApiModelProperty@field)(required = true, value = "The attributes of the entity")
                              attributes: Option[Map[String, String]] = None)