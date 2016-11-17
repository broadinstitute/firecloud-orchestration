package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.{FireCloudException, model}
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import spray.http.StatusCode
import spray.http.StatusCodes.BadRequest
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, FireCloudPermission}
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing.{MalformedRequestContentRejection, RejectionHandler}
import spray.routing.directives.RouteDirectives.complete

//Mix in one of these with your AttributeFormat so you can serialize lists
//This also needs mixing in with an AttributeFormat because they're symbiotic
sealed trait AttributeListSerializer {
  def writeListType(obj: Attribute): JsValue
  def readListType(json: JsValue): Attribute

  def writeAttribute(obj: Attribute): JsValue
  def readAttribute(json: JsValue): Attribute
}

//Serializes attribute lists to e.g. [1,2,3]
//When reading in JSON, assumes that [] is an empty value list, not an empty ref list
trait PlainArrayAttributeListSerializer extends AttributeListSerializer {
  override def writeListType(obj: Attribute): JsValue = obj match {
    //lists
    case AttributeValueEmptyList => JsArray()
    case AttributeValueList(l) => JsArray(l.map(writeAttribute):_*)
    case AttributeEntityReferenceEmptyList => JsArray()
    case AttributeEntityReferenceList(l) => JsArray(l.map(writeAttribute):_*)
    case _ => throw new FireCloudException("you can't pass a non-list to writeListType")
  }

  override def readListType(json: JsValue): Attribute = json match {
    case JsArray(a) =>
      val attrList: Seq[Attribute] = a.map(readAttribute)
      attrList match {
        case e: Seq[_] if e.isEmpty => AttributeValueEmptyList
        case v: Seq[AttributeValue @unchecked] if attrList.map(_.isInstanceOf[AttributeValue]).reduce(_&&_) => AttributeValueList(v)
        case r: Seq[AttributeEntityReference @unchecked] if attrList.map(_.isInstanceOf[AttributeEntityReference]).reduce(_&&_) => AttributeEntityReferenceList(r)
        case _ => throw new DeserializationException("illegal array type")
      }
    case _ => throw new DeserializationException("unexpected json type")
  }
}

//Serializes attribute lists to e.g. { "itemsType" : "AttributeValue", "items" : [1,2,3] }
trait TypedAttributeListSerializer extends AttributeListSerializer {
  val LIST_ITEMS_TYPE_KEY = "itemsType"
  val LIST_ITEMS_KEY = "items"
  val LIST_OBJECT_KEYS = Set(LIST_ITEMS_TYPE_KEY, LIST_ITEMS_KEY)

  val VALUE_LIST_TYPE = "AttributeValue"
  val REF_LIST_TYPE = "EntityReference"
  val ALLOWED_LIST_TYPES = Seq(VALUE_LIST_TYPE, REF_LIST_TYPE)

  def writeListType(obj: Attribute): JsValue = obj match {
    //lists
    case AttributeValueEmptyList => writeAttributeList(VALUE_LIST_TYPE, Seq.empty[AttributeValue])
    case AttributeValueList(l) => writeAttributeList(VALUE_LIST_TYPE, l)
    case AttributeEntityReferenceEmptyList => writeAttributeList(REF_LIST_TYPE, Seq.empty[AttributeEntityReference])
    case AttributeEntityReferenceList(l) => writeAttributeList(REF_LIST_TYPE, l)
    case _ => throw new FireCloudException("you can't pass a non-list to writeListType")
  }

  def readListType(json: JsValue): Attribute = json match {
    case JsObject(members) if LIST_OBJECT_KEYS subsetOf members.keySet => readAttributeList(members)

    case _ => throw new DeserializationException("unexpected json type")
  }

  def writeAttributeList[T <: Attribute](listType: String, list: Seq[T]): JsValue = {
    JsObject( Map(LIST_ITEMS_TYPE_KEY -> JsString(listType), LIST_ITEMS_KEY -> JsArray(list.map( writeAttribute ).toSeq:_*)) )
  }

  def readAttributeList(jsMap: Map[String, JsValue]) = {
    val attrList: Seq[Attribute] = jsMap(LIST_ITEMS_KEY) match {
      case JsArray(elems) => elems.map(readAttribute)
      case _ => throw new DeserializationException(s"the value of %s should be an array".format(LIST_ITEMS_KEY))
    }

    (jsMap(LIST_ITEMS_TYPE_KEY), attrList) match {
      case (JsString(VALUE_LIST_TYPE), vals: Seq[AttributeValue @unchecked]) if attrList.isEmpty => AttributeValueEmptyList
      case (JsString(VALUE_LIST_TYPE), vals: Seq[AttributeValue @unchecked]) if attrList.map(_.isInstanceOf[AttributeValue]).reduce(_&&_) => AttributeValueList(vals)

      case (JsString(REF_LIST_TYPE), refs: Seq[AttributeEntityReference @unchecked]) if attrList.isEmpty => AttributeEntityReferenceEmptyList
      case (JsString(REF_LIST_TYPE), refs: Seq[AttributeEntityReference @unchecked]) if attrList.map(_.isInstanceOf[AttributeEntityReference]).reduce(_&&_) => AttributeEntityReferenceList(refs)

      case (JsString(s), _) if !ALLOWED_LIST_TYPES.contains(s) => throw new DeserializationException(s"illegal array type: $LIST_ITEMS_TYPE_KEY must be one of ${ALLOWED_LIST_TYPES.mkString(", ")}")
      case _ => throw new DeserializationException("illegal array type: array elements don't match array type")
    }
  }
}

object ModelJsonProtocol {

  implicit object impStatusCode extends JsonFormat[StatusCode] {
    override def write(code: StatusCode): JsValue = JsNumber(code.intValue)

    override def read(json: JsValue): StatusCode = json match {
      case JsNumber(n) => n.intValue
      case _ => throw DeserializationException("unexpected json type")
    }
  }

  implicit object impLibrarySearchParams extends RootJsonFormat[LibrarySearchParams] {
    val SEARCH_TERM = "searchTerm"
    val FROM = "from"
    val SIZE = "size"

    override def write(params: LibrarySearchParams): JsValue = params.searchTerm match {
      case None => JsObject(Map(FROM -> JsNumber(params.from), SIZE -> JsNumber(params.size)))
      case Some(term) => JsObject(Map(SEARCH_TERM -> JsString(term), FROM -> JsNumber(params.from), SIZE -> JsNumber(params.size)))
    }

    override def read(json: JsValue): LibrarySearchParams = {
      val data = json.asJsObject.fields
      val term = data.getOrElse(SEARCH_TERM, None) match {
        case JsString(str) => Some(str)
        case None => None
        case _ => throw DeserializationException("unexpected json type for " + SEARCH_TERM)
      }
      val from: Option[Int] = data.getOrElse(FROM, None) match {
        case JsNumber(f) => Some(f.intValue)
        case None => None
        case _ => throw DeserializationException("unexpected json type for " + FROM)
      }
      val size: Option[Int] = data.getOrElse(SIZE, None) match {
        case JsNumber(s) => Some(s.intValue)
        case None => None
        case _ => throw DeserializationException("unexpected json type for " + SIZE)
      }

      (from, size) match {
        case (None, None) => LibrarySearchParams(term)
        case (Some(f), None) => LibrarySearchParams(term, f)
        case (None, Some(s)) => LibrarySearchParams(term, size=s)
        case (Some(f), Some(s)) => LibrarySearchParams(term, f, s)
      }
    }
  }

  implicit object impQueryMap extends JsonFormat[QueryMap] {
    override def write(inputmap: QueryMap): JsValue = inputmap match {
      case matchall : ESMatchAll => matchall.toJson
      case wildcard : ESWildcard => wildcard.toJson
      case _ => throw new SerializationException("unexpected QueryMap type")
    }

    override def read(json: JsValue): QueryMap = {
      json.asJsObject.fields.keys.head match {
        case "match_all" => impESMatchAll.read(json)
        case "wildcard" => impESWildcard.read(json)
        case _ => throw DeserializationException("unexpected json type")
      }
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
        case _ => throw DeserializationException("unable to deserialize StackTraceElement")
      }
  }

  // see https://github.com/spray/spray-json#jsonformats-for-recursive-types
  implicit val impErrorReport: RootJsonFormat[ErrorReport] = rootFormat(lazyFormat(jsonFormat5(ErrorReport)))

  implicit object AttributeNameFormat extends RootJsonFormat[AttributeName] {
    override def write(an: AttributeName): JsValue = JsString(AttributeName.toDelimitedName(an))

    override def read(json: JsValue): AttributeName = json match {
      case JsString(name) => AttributeName.fromDelimitedName(name)
      case _ => throw new DeserializationException("unexpected json type")
    }
  }

  trait AttributeFormat extends RootJsonFormat[Attribute] with AttributeListSerializer {
    //Magic strings we use in JSON serialization

    //Entity refs get serialized to e.g. { "entityType" : "sample", "entityName" : "theBestSample" }
    val ENTITY_TYPE_KEY = "entityType"
    val ENTITY_NAME_KEY = "entityName"
    val ENTITY_OBJECT_KEYS = Set(ENTITY_TYPE_KEY, ENTITY_NAME_KEY)

    override def write(obj: Attribute): JsValue = writeAttribute(obj)
    def writeAttribute(obj: Attribute): JsValue = obj match {
      //vals
      case AttributeNull => JsNull
      case AttributeBoolean(b) => JsBoolean(b)
      case AttributeNumber(n) => JsNumber(n)
      case AttributeString(s) => JsString(s)
      //ref
      case AttributeEntityReference(entityType, entityName) => JsObject(Map(ENTITY_TYPE_KEY -> JsString(entityType), ENTITY_NAME_KEY -> JsString(entityName)))
      //list types
      case x: AttributeList[_] => writeListType(x)

      case _ => throw new SerializationException("AttributeFormat doesn't know how to write JSON for type " + obj.getClass.getSimpleName)
    }

    override def read(json: JsValue): Attribute = readAttribute(json)
    def readAttribute(json: JsValue): Attribute = json match {
      case JsNull => AttributeNull
      case JsString(s) => AttributeString(s)
      case JsBoolean(b) => AttributeBoolean(b)
      case JsNumber(n) => AttributeNumber(n)

      case JsObject(members) if ENTITY_OBJECT_KEYS subsetOf members.keySet =>
        AttributeEntityReference(members(ENTITY_TYPE_KEY).asInstanceOf[JsString].value, members(ENTITY_NAME_KEY).asInstanceOf[JsString].value)

      case _ => readListType(json)
    }
  }

  implicit val impAttributeFormat: AttributeFormat = new AttributeFormat with TypedAttributeListSerializer

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

  implicit val impEntityUpdateDefinition = jsonFormat3(EntityUpdateDefinition)

  implicit val impFireCloudKeyValue = jsonFormat2(FireCloudKeyValue)
  implicit val impThurloeKeyValue = jsonFormat2(ThurloeKeyValue)
  implicit val impBasicProfile = jsonFormat11(BasicProfile)
  implicit val impProfile = jsonFormat15(Profile.apply)
  implicit val impProfileWrapper = jsonFormat2(ProfileWrapper)

  implicit val impNotification = jsonFormat4(ThurloeNotification)

  implicit val impTokenResponse = jsonFormat6(OAuthTokens.apply)
  implicit val impRawlsToken = jsonFormat1(RawlsToken)
  implicit val impRawlsTokenDate = jsonFormat1(RawlsTokenDate)

  implicit val impJWTWrapper = jsonFormat1(JWTWrapper)
  implicit val impNihStatus = jsonFormat7(NIHStatus)

  implicit val impOAuthUser = jsonFormat2(OAuthUser)

  implicit val impRawlsUserInfo = jsonFormat2(RawlsUserInfo)
  implicit val impRawlsEnabled = jsonFormat2(RawlsEnabled)
  implicit val impRegistrationInfo = jsonFormat2(RegistrationInfo)
  implicit val impCurator = jsonFormat1(Curator)

  implicit val impRawlsGroupMemberList = jsonFormat4(RawlsGroupMemberList)

  implicit val impBillingAccountScopes = jsonFormat1(BillingAccountScopes)
  implicit val impBillingAccountRedirect = jsonFormat1(BillingAccountRedirect)

  implicit val impGoogleObjectMetadata = jsonFormat15(ObjectMetadata)

  implicit val AttributeDetailFormat: RootJsonFormat[AttributeDetail] = rootFormat(lazyFormat(jsonFormat2(AttributeDetail)))
  implicit val AttributeDefinitionFormat = jsonFormat1(AttributeDefinition)

  implicit val ESDetailFormat = jsonFormat1(ESDetail)
  implicit val ESDatasetPropertyFormat = jsonFormat1(ESDatasetProperty)

  implicit val impLibrarySearchResponse = jsonFormat3(LibrarySearchResponse)

  implicit val impESQuery = jsonFormat1(ESQuery)
  implicit val impESWildcardSearchTerm = jsonFormat1(ESWildcardSearchTerm)
  implicit val impESMatchAll = jsonFormat1(ESMatchAll)
  implicit val impESWildcard = jsonFormat1(ESWildcard)

  // don't make this implicit! It would be pulled in by anything including ModelJsonProtocol._
  val entityExtractionRejectionHandler = RejectionHandler {
    case MalformedRequestContentRejection(errorMsg, _) :: _ =>
      complete(BadRequest, errorMsg)
  }

  // See http://stackoverflow.com/questions/24526103/generic-spray-client and
  // https://gist.github.com/mikemckibben/fad4328de85a79a06bf3
  implicit def rootEitherFormat[A : RootJsonFormat, B : RootJsonFormat] = new RootJsonFormat[Either[A, B]] {
    val format = DefaultJsonProtocol.eitherFormat[A, B]
    def write(either: Either[A, B]) = format.write(either)
    def read(value: JsValue) = format.read(value)
  }

}
