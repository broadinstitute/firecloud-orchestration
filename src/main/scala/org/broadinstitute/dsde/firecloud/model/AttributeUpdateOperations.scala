package org.broadinstitute.dsde.firecloud.model

import spray.json._
import org.broadinstitute.dsde.rawls.model.{AttributeName, Attribute}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

/**
 * Created by mbemis on 7/23/15.
 *
 * TODO: share with rawls code, instead of copying wholesale
 */
object AttributeUpdateOperations {
  import spray.json.DefaultJsonProtocol._

  sealed trait AttributeUpdateOperation
  case class AddUpdateAttribute(attributeName: AttributeName, addUpdateAttribute: Attribute) extends AttributeUpdateOperation
  case class RemoveAttribute(attributeName: AttributeName) extends AttributeUpdateOperation
  case class AddListMember(attributeListName: AttributeName, newMember: Attribute) extends AttributeUpdateOperation
  case class RemoveListMember(attributeListName: AttributeName, removeMember: Attribute) extends AttributeUpdateOperation

  private val AddUpdateAttributeFormat = jsonFormat2(AddUpdateAttribute)
  private val RemoveAttributeFormat = jsonFormat1(RemoveAttribute)
  private val AddListMemberFormat = jsonFormat2(AddListMember)
  private val RemoveListMemberFormat = jsonFormat2(RemoveListMember)

  implicit object AttributeUpdateOperationFormat extends RootJsonFormat[AttributeUpdateOperation] {

    override def write(obj: AttributeUpdateOperation): JsValue = {
      val json = obj match {
        case x: AddUpdateAttribute => AddUpdateAttributeFormat.write(x)
        case x: RemoveAttribute => RemoveAttributeFormat.write(x)
        case x: AddListMember => AddListMemberFormat.write(x)
        case x: RemoveListMember => RemoveListMemberFormat.write(x)
      }

      JsObject(json.asJsObject.fields + ("op" -> JsString(obj.getClass.getSimpleName)))
    }

    override def read(json: JsValue) : AttributeUpdateOperation = json match {
      case JsObject(fields) =>
        val op = fields.getOrElse("op", throw new DeserializationException("missing op property"))
        op match {
          case JsString("AddUpdateAttribute") => AddUpdateAttributeFormat.read(json)
          case JsString("RemoveAttribute") => RemoveAttributeFormat.read(json)
          case JsString("AddListMember") => AddListMemberFormat.read(json)
          case JsString("RemoveListMember") => RemoveListMemberFormat.read(json)
          case x => throw new DeserializationException("unrecognized op: " + x)
        }

      case _ => throw new DeserializationException("unexpected json type")
    }
  }

  case class EntityUpdateDefinition(
                        name: String,
                        entityType: String,
                        operations: Seq[AttributeUpdateOperation]
  )

  implicit val entityUpdateDefinitionFormat = jsonFormat3(EntityUpdateDefinition)
}
