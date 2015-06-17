package org.broadinstitute.dsde.firecloud.model

import com.wordnik.swagger.annotations.{ApiModel, ApiModelProperty}
import spray.json.DefaultJsonProtocol

import scala.annotation.meta.field

object MethodEntityJsonProtocol extends DefaultJsonProtocol {
  implicit val impMethodEntity = jsonFormat10(MethodEntity)
}

@ApiModel(value = "Agora Method")
case class MethodEntity(
                         @(ApiModelProperty@field)(value = "The namespace to which the method belongs")
                         namespace: Option[String] = None,
                         @(ApiModelProperty@field)(value = "The method name ")
                         name: Option[String] = None,
                         @(ApiModelProperty@field)(value = "The method snapshot id")
                         snapshotId: Option[Int] = None,
                         @(ApiModelProperty@field)(value = "A short description of the method")
                         synopsis: Option[String] = None,
                         @(ApiModelProperty@field)(value = "Method documentation")
                         documentation: Option[String] = None,
                         @(ApiModelProperty@field)(value = "User who owns this method in the methods repo")
                         owner: Option[String] = None,
                         @(ApiModelProperty@field)(value = "The date the method was inserted in the methods repo")
                         createDate: Option[String] = None,
                         @(ApiModelProperty@field)(value = "The method payload")
                         payload: Option[String] = None,
                         @(ApiModelProperty@field)(value = "URI for method details")
                         url: Option[String] = None,
                         @(ApiModelProperty@field)(dataType = "string", value = "The type of the entity (Task, Workflow, Configuration)")
                         entityType: Option[String] = None)