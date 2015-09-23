package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import spray.json._
import spray.json.DefaultJsonProtocol._

object ModelJsonProtocol {

  implicit val impMethod = jsonFormat8(MethodRepository.Method)
  implicit val impConfiguration = jsonFormat9(MethodRepository.Configuration)

  implicit val impWorkspaceName = jsonFormat2(WorkspaceName)
  implicit val impWorkspaceEntity = jsonFormat5(WorkspaceEntity)

  implicit val impEntity = jsonFormat5(Entity)
  implicit val impEntityCreateResult = jsonFormat4(EntityCreateResult)
  implicit val impEntityWithType = jsonFormat3(EntityWithType)

  implicit val impMethodConfiguration = jsonFormat9(MethodConfiguration)
  implicit val impMethodConfigurationRename = jsonFormat3(MethodConfigurationRename)

  implicit val impDestination = jsonFormat3(Destination)
  implicit val impMethodConfigurationCopy = jsonFormat4(MethodConfigurationCopy)
  implicit val impConfigurationCopyIngest = jsonFormat5(CopyConfigurationIngest)

  implicit val impEntityMetadata = jsonFormat4(EntityMetadata)
  implicit val impModelSchema = jsonFormat1(EntityModel)
  implicit val impSubmissionIngest = jsonFormat5(SubmissionIngest)

  implicit object impAttributeFormat extends RootJsonFormat[Attribute] {

    override def write(obj: Attribute): JsValue = obj match {
      case AttributeNull() => JsNull
      case AttributeString(s) => JsString(s)
      case AttributeReference(entityType, entityName) => JsObject(Map("entityType" -> JsString(entityType), "entityName" -> JsString(entityName)))
    }

    override def read(json: JsValue): Attribute = json match {
      case JsNull => AttributeNull()
      case JsString(s) => AttributeString(s)
      case JsObject(members) => AttributeReference(members("entityType").asInstanceOf[JsString].value, members("entityName").asInstanceOf[JsString].value)
      case _ => throw new DeserializationException("unexpected json type")
    }
  }

  implicit val impEntityUpdateDefinition = jsonFormat3(EntityUpdateDefinition)
}
