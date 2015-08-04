package org.broadinstitute.dsde.firecloud.model

import spray.json.DeserializationException
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsString, JsValue, RootJsonFormat}

object ModelJsonProtocol {

  implicit val impMethod = jsonFormat8(MethodRepository.Method)
  implicit val impConfiguration = jsonFormat9(MethodRepository.Configuration)

  implicit val impWorkspaceName = jsonFormat2(WorkspaceName)
  implicit val impWorkspaceEntity = jsonFormat5(WorkspaceEntity)

  implicit val impEntity = jsonFormat5(Entity)
  implicit val impEntityCreateResult = jsonFormat4(EntityCreateResult)

  implicit val impMethodConfiguration = jsonFormat9(MethodConfiguration)

  implicit val impDestination = jsonFormat3(Destination)
  implicit val impMethodConfigurationCopy = jsonFormat4(MethodConfigurationCopy)

  implicit val impEntityMetadata = jsonFormat3(EntityMetadata)
  implicit val impModelSchema = jsonFormat1(EntityModel)

  implicit object impAttributeFormat extends RootJsonFormat[Attribute] {

    override def write(obj: Attribute): JsValue = obj match {
      case AttributeString(s) => JsString(s)
      case AttributeReference(entityType, entityName) => JsObject(Map("entityType" -> JsString(entityType), "entityName" -> JsString(entityName)))
    }

    override def read(json: JsValue): Attribute = json match {
      case JsString(s) => AttributeString(s)
      case JsObject(members) => AttributeReference(members("entityType").asInstanceOf[JsString].value, members("entityName").asInstanceOf[JsString].value)
      case _ => throw new DeserializationException("unexpected json type")
    }
  }

  implicit val impEntityUpdateDefinition = jsonFormat3(EntityUpdateDefinition)
}
