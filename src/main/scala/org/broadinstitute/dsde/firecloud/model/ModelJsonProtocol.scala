package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import spray.http.StatusCode
import spray.http.StatusCodes.BadRequest
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, FireCloudPermission}
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing.{MalformedRequestContentRejection, RejectionHandler}
import spray.routing.directives.RouteDirectives.complete

object ModelJsonProtocol {

  implicit val impMethod = jsonFormat8(MethodRepository.Method)
  implicit val impConfiguration = jsonFormat9(MethodRepository.Configuration)

  implicit val impWorkspaceName = jsonFormat2(WorkspaceName)
  implicit val impWorkspaceEntity = jsonFormat5(WorkspaceEntity)
  implicit val impWorkspaceCreate = jsonFormat4(WorkspaceCreate)
  implicit val impRawlsWorkspaceCreate = jsonFormat4(RawlsWorkspaceCreate)

  implicit val impSubmissionStats = jsonFormat3(SubmissionStats)
  implicit val impRawlsWorkspace = jsonFormat11(RawlsWorkspace)
  implicit val impRawlsWorkspaceResponse = jsonFormat4(RawlsWorkspaceResponse)
  implicit val impUIWorkspace = jsonFormat12(UIWorkspace)
  implicit val impUIWorkspaceResponse = jsonFormat4(UIWorkspaceResponse)

  implicit val impEntity = jsonFormat5(Entity)
  implicit val impEntityCreateResult = jsonFormat4(EntityCreateResult)
  implicit val impEntityWithType = jsonFormat3(EntityWithType)
  implicit val impEntityCopyDefinition = jsonFormat3(EntityCopyDefinition)
  implicit val impEntityCopyWithDestinationDefinition = jsonFormat4(EntityCopyWithDestinationDefinition)
  implicit val impEntityId = jsonFormat2(EntityId)
  implicit val impEntityDelete = jsonFormat2(EntityDeleteDefinition)

  implicit val impMethodConfiguration = jsonFormat8(MethodConfiguration)
  implicit val impMethodConfigurationRename = jsonFormat3(MethodConfigurationRename)

  implicit val impDestination = jsonFormat3(MethodConfigurationId)
  implicit val impMethodConfigurationCopy = jsonFormat4(MethodConfigurationCopy)
  implicit val impConfigurationCopyIngest = jsonFormat5(CopyConfigurationIngest)
  implicit val impMethodConfigurationPublish = jsonFormat3(MethodConfigurationPublish)
  implicit val impPublishConfigurationIngest = jsonFormat4(PublishConfigurationIngest)

  implicit val impFireCloudPermission = jsonFormat2(FireCloudPermission)
  implicit val impAgoraPermission = jsonFormat2(AgoraPermission)

  implicit val impEntityMetadata = jsonFormat4(EntityMetadata)
  implicit val impModelSchema = jsonFormat1(EntityModel)
  implicit val impSubmissionIngest = jsonFormat5(SubmissionIngest)

  implicit object impStatusCode extends JsonFormat[StatusCode] {
    override def write(code: StatusCode): JsValue = JsNumber(code.intValue)

    override def read(json: JsValue): StatusCode = json match {
      case JsNumber(n) => n.intValue
      case _ => throw new DeserializationException("unexpected json type")
    }
  }

  implicit object impStackTraceElement extends RootJsonFormat[StackTraceElement] {
    val CLASS_NAME = "className"
    val METHOD_NAME = "methodName"
    val FILE_NAME = "fileName"
    val LINE_NUMBER = "lineNumber"

    def write(stackTraceElement: StackTraceElement) =
      JsObject(CLASS_NAME -> JsString(stackTraceElement.getClassName),
        METHOD_NAME -> JsString(stackTraceElement.getMethodName),
        FILE_NAME -> JsString(stackTraceElement.getFileName),
        LINE_NUMBER -> JsNumber(stackTraceElement.getLineNumber))

    def read(json: JsValue) =
      json.asJsObject.getFields(CLASS_NAME, METHOD_NAME, FILE_NAME, LINE_NUMBER) match {
        case Seq(JsString(className), JsString(methodName), JsString(fileName), JsNumber(lineNumber)) =>
          new StackTraceElement(className, methodName, fileName, lineNumber.toInt)
        case _ => throw new DeserializationException("unable to deserialize StackTraceElement")
      }
  }

  // see https://github.com/spray/spray-json#jsonformats-for-recursive-types
  implicit val impErrorReport: RootJsonFormat[ErrorReport] = rootFormat(lazyFormat(jsonFormat5(ErrorReport)))

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

  implicit val impFireCloudKeyValue = jsonFormat2(FireCloudKeyValue)
  implicit val impThurloeKeyValue = jsonFormat2(ThurloeKeyValue)
  implicit val impBasicProfile = jsonFormat11(BasicProfile)
  implicit val impProfile = jsonFormat15(Profile.apply)
  implicit val impProfileWrapper = jsonFormat2(ProfileWrapper)

  implicit val impNotification = jsonFormat3(ThurloeNotification)

  implicit val impTokenResponse = jsonFormat6(OAuthTokens.apply)
  implicit val impRawlsToken = jsonFormat1(RawlsToken)
  implicit val impRawlsTokenDate = jsonFormat1(RawlsTokenDate)

  implicit val impJWTWrapper = jsonFormat1(JWTWrapper)
  implicit val impNihStatus = jsonFormat7(NIHStatus)

  implicit val impRawlsUserInfo = jsonFormat2(RawlsUserInfo)
  implicit val impRawlsEnabled = jsonFormat2(RawlsEnabled)
  implicit val impRegistrationInfo = jsonFormat2(RegistrationInfo)

  implicit val impRawlsGroupMemberList = jsonFormat4(RawlsGroupMemberList)

  // don't make this implicit! It would be pulled in by anything including ModelJsonProtocol._
  val entityExtractionRejectionHandler = RejectionHandler {
    case MalformedRequestContentRejection(errorMsg, _) :: _ =>
      complete(BadRequest, errorMsg)
  }

}
