package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.FireCloudException
import spray.http.StatusCode
import spray.http.StatusCodes.BadRequest
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, FireCloudPermission}
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing.{MalformedRequestContentRejection, RejectionHandler}
import spray.routing.directives.RouteDirectives.complete

import scala.util.Try

//Mix in one of these with your AttributeFormat so you can serialize lists
//This also needs mixing in with an AttributeFormat because they're symbiotic
sealed trait AttributeListSerializer {
  def writeListType(obj: Attribute): JsValue

  //distinguish between lists and RawJson types here
  def readComplexType(json: JsValue): Attribute

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

  override def readComplexType(json: JsValue): Attribute = json match {
    case JsArray(a) =>
      val attrList: Seq[Attribute] = a.map(readAttribute)
      attrList match {
        case e: Seq[_] if e.isEmpty => AttributeValueEmptyList
        case v: Seq[AttributeValue @unchecked] if attrList.map(_.isInstanceOf[AttributeValue]).reduce(_&&_) => AttributeValueList(v)
        case r: Seq[AttributeEntityReference @unchecked] if attrList.map(_.isInstanceOf[AttributeEntityReference]).reduce(_&&_) => AttributeEntityReferenceList(r)
        case _ => AttributeValueRawJson(json) //heterogeneous array type? ok, we'll treat it as raw json
      }
    case _ => AttributeValueRawJson(json) //something else? ok, we'll treat it as raw json
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

  def readComplexType(json: JsValue): Attribute = json match {
    case JsObject(members) if LIST_OBJECT_KEYS subsetOf members.keySet => readAttributeList(members)

    //in this serializer, [1,2,3] is not the representation for an AttributeValueList, so it's raw json
    case _ => AttributeValueRawJson(json)
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

  def optionalEntryIntReader(fieldName: String, data: Map[String,JsValue]): Option[Int] = {
    optionalEntryReader[Option[Int]](fieldName, data, _.convertTo[Option[Int]], None)
  }

  /**
    * optionalEntryReader constructs a type from a json map for a field that may or may not be in the map.
    * if the field is missing from the map, the default passed in will be returned. This class does not necessarily
    * have to return an option. It will if the type T is an Option.
    * @param fieldName the field that may or may not exist in the map
    * @param data the map to check
    * @param converter the convert to method for the type T (you can pass the parameter as: _.convertTo[T] where you replace T with the actual type)
    * @param default what to return if the field is not in the map
    * @tparam T the type of the object the data represents and will be converted to
    * @return on object of the specified type constructed from the data if field is in the map, or the default if not
    */
  def optionalEntryReader[T](fieldName: String, data: Map[String,JsValue], converter: JsValue => T, default: T): T = {
    data.getOrElse(fieldName, None) match {
      case j:JsValue => Try(converter(j)).toOption.getOrElse(
        throw DeserializationException(s"unexpected json type for $fieldName")
      )
      case None => default
    }
  }

  implicit object impStatusCode extends JsonFormat[StatusCode] {
    override def write(code: StatusCode): JsValue = JsNumber(code.intValue)

    override def read(json: JsValue): StatusCode = json match {
      case JsNumber(n) => n.intValue
      case _ => throw DeserializationException("unexpected json type")
    }
  }

  implicit object impLibrarySearchParams extends RootJsonFormat[LibrarySearchParams] {
    val SEARCH_STRING = "searchString"
    val FILTERS = "filters"
    val FIELD_AGGREGATIONS = "fieldAggregations"
    val MAX_AGGREGATIONS = "maxAggregations"
    val FROM = "from"
    val SIZE = "size"

    override def write(params: LibrarySearchParams): JsValue = {
      val fields:Seq[Option[(String, JsValue)]] = Seq(
        Some(FILTERS -> params.filters.toJson),
        Some(FIELD_AGGREGATIONS -> params.fieldAggregations.toJson),
        Some(FROM -> params.from.toJson),
        Some(SIZE -> params.size.toJson),
        params.searchString map {SEARCH_STRING -> JsString(_)}
      )

      JsObject( fields.filter(_.isDefined).map{_.get}.toMap )
    }

    override def read(json: JsValue): LibrarySearchParams = {
      val data = json.asJsObject.fields
      val term = data.getOrElse(SEARCH_STRING, None) match {
        case JsString(str) if str.trim == "" => None
        case JsString(str) => Some(str.trim)
        case None => None
        case _ => throw DeserializationException(s"unexpected json type for $SEARCH_STRING")
      }

      val filters = optionalEntryReader[Map[String, Seq[String]]](FILTERS, data, _.convertTo[Map[String, Seq[String]]], Map.empty)
      val aggs = optionalEntryReader[Map[String, Int]](FIELD_AGGREGATIONS, data, _.convertTo[Map[String, Int]], Map.empty)
      val from = optionalEntryIntReader(FROM, data)
      val size = optionalEntryIntReader(SIZE, data)

      LibrarySearchParams(term, filters, aggs, from, size)
    }
  }

  implicit object impESPropertyFields extends JsonFormat[ESPropertyFields] {
    override def write(input: ESPropertyFields): JsValue = input match {
      case estype: ESType => estype.toJson
      case esinternaltype: ESInternalType => esinternaltype.toJson
      case esinnerfield: ESInnerField => esinnerfield.toJson
      case _ => throw new SerializationException("unexpected ESProperty type")
    }

    override def read(json: JsValue): ESPropertyFields = {
      val data = json.asJsObject.fields
      if (data.contains("fields")) {
        ESTypeFormat.read(json)
      } else {
        ESInternalTypeFormat.read(json)
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
      case AttributeValueRawJson(j) => j
      //ref
      case AttributeEntityReference(entityType, entityName) => JsObject(Map(ENTITY_TYPE_KEY -> JsString(entityType), ENTITY_NAME_KEY -> JsString(entityName)))
      //list types
      case x: AttributeList[_] => writeListType(x)

      case _ => throw new SerializationException(s"AttributeFormat doesn't know how to write JSON for type $obj.getClass.getSimpleName")
    }

    override def read(json: JsValue): Attribute = readAttribute(json)
    def readAttribute(json: JsValue): Attribute = json match {
      case JsNull => AttributeNull
      case JsString(s) => AttributeString(s)
      case JsBoolean(b) => AttributeBoolean(b)
      case JsNumber(n) => AttributeNumber(n)
      //NOTE: we handle AttributeValueRawJson in readComplexType below

      case JsObject(members) if ENTITY_OBJECT_KEYS subsetOf members.keySet => (members(ENTITY_TYPE_KEY), members(ENTITY_NAME_KEY)) match {
        case (JsString(typeKey), JsString(nameKey)) => AttributeEntityReference(typeKey, nameKey)
        case _ => throw DeserializationException(s"the values for $ENTITY_TYPE_KEY and $ENTITY_NAME_KEY must be strings")
      }

      case _ => readComplexType(json)
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
  implicit val impRawlsWorkspaceResponse = jsonFormat5(RawlsWorkspaceResponse)
  implicit val impUIWorkspace = jsonFormat12(UIWorkspace)
  implicit val impUIWorkspaceResponse = jsonFormat5(UIWorkspaceResponse)

  implicit val impEntity = jsonFormat5(Entity)
  implicit val impEntityCreateResult = jsonFormat4(EntityCreateResult)
  implicit val impRawlsEntity = jsonFormat3(RawlsEntity)
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

  implicit val impEntityMetadata = jsonFormat3(EntityMetadata)
  implicit val impModelSchema = jsonFormat1(EntityModel)
  implicit val impSubmissionIngest = jsonFormat5(SubmissionIngest)

  implicit val impEntityUpdateDefinition = jsonFormat3(EntityUpdateDefinition)

  implicit val impFireCloudKeyValue = jsonFormat2(FireCloudKeyValue)
  implicit val impThurloeKeyValue = jsonFormat2(ThurloeKeyValue)
  implicit val impBasicProfile = jsonFormat11(BasicProfile)
  implicit val impProfile = jsonFormat15(Profile.apply)
  implicit val impProfileWrapper = jsonFormat2(ProfileWrapper)

  implicit val impNotification = jsonFormat5(ThurloeNotification)

  implicit val impTokenResponse = jsonFormat6(OAuthTokens.apply)
  implicit val impRawlsToken = jsonFormat1(RawlsToken)
  implicit val impRawlsTokenDate = jsonFormat1(RawlsTokenDate)

  implicit val impJWTWrapper = jsonFormat1(JWTWrapper)

  implicit val impOAuthUser = jsonFormat2(OAuthUser)

  implicit val impRawlsUserInfo = jsonFormat2(RawlsUserInfo)
  implicit val impRawlsEnabled = jsonFormat2(RawlsEnabled)
  implicit val impRegistrationInfo = jsonFormat2(RegistrationInfo)
  implicit val impCurator = jsonFormat1(Curator)

  implicit val impRawlsGroupMemberList = jsonFormat4(RawlsGroupMemberList)

  implicit val impRawlsBucketUsageResponse = jsonFormat1(RawlsBucketUsageResponse)
  implicit val impWorkspaceStorageCostEstimate = jsonFormat1(WorkspaceStorageCostEstimate)

  implicit val impGoogleObjectMetadata = jsonFormat16(ObjectMetadata)

  implicit val AttributeDetailFormat: RootJsonFormat[AttributeDetail] = rootFormat(lazyFormat(jsonFormat4(AttributeDetail)))
  implicit val AttributeDefinitionFormat = jsonFormat1(AttributeDefinition)

  implicit val ESInnerFieldFormat = jsonFormat6(ESInnerField)
  implicit val ESInternalTypeFormat = jsonFormat3(ESInternalType)
  implicit val ESTypeFormat = jsonFormat3(ESType.apply)
  implicit val ESDatasetPropertiesFormat = jsonFormat1(ESDatasetProperty)

  implicit val impAggregationTermResult = jsonFormat2(AggregationTermResult)
  implicit val impAggregationFieldResults = jsonFormat2(AggregationFieldResults)
  implicit val impLibraryAggregationResponse = jsonFormat2(LibraryAggregationResponse)
  implicit val impLibrarySearchResponse = jsonFormat4(LibrarySearchResponse)
  
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
