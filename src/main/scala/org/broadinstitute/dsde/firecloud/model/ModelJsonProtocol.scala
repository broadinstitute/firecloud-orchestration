package org.broadinstitute.dsde.firecloud.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedRequestContentRejection, RejectionHandler}
import org.broadinstitute.dsde.firecloud.model.DataUse._
import org.broadinstitute.dsde.firecloud.model.ManagedGroupRoles.ManagedGroupRole
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository._
import org.broadinstitute.dsde.firecloud.model.Ontology.{ESTermParent, TermParent, TermResource}
import org.broadinstitute.dsde.firecloud.model.Project.ProjectRoles.ProjectRole
import org.broadinstitute.dsde.firecloud.model.Project._
import org.broadinstitute.dsde.firecloud.model.SamResource.{AccessPolicyName, ResourceId, UserPolicy}
import org.broadinstitute.dsde.firecloud.model.ShareLog.{Share, ShareType}
import org.broadinstitute.dsde.rawls.model.UserModelJsonSupport._
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport.WorkspaceAccessLevelFormat
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.workbench.model.ValueObjectFormat
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport.InstantFormat
import spray.json._

import scala.util.{Failure, Success, Try}

//noinspection TypeAnnotation,RedundantNewCaseClass
object ModelJsonProtocol extends WorkspaceJsonSupport with SprayJsonSupport {
  import spray.json.DefaultJsonProtocol._

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


  implicit object impDiseaseOntologyNodeId extends RootJsonFormat[DiseaseOntologyNodeId]  {
    override def write(obj: DiseaseOntologyNodeId): JsValue = JsString(obj.uri.toString)

    override def read(json: JsValue): DiseaseOntologyNodeId = json match {
      case JsString(uristring) => DiseaseOntologyNodeId(uristring)
      case _ => throw DeserializationException(s"cannot deserialize DiseaseOntologyNodeId from [$json]")
    }
  }
  implicit val impResearchPurpose: RootJsonFormat[ResearchPurpose] = jsonFormat6(ResearchPurpose.apply)
  implicit val impResearchPurposeRequest: RootJsonFormat[ResearchPurposeRequest] = jsonFormat7(ResearchPurposeRequest.apply)

  implicit object impLibrarySearchParams extends RootJsonFormat[LibrarySearchParams] {
    val SEARCH_STRING = "searchString"
    val FILTERS = "filters"
    val RESEARCH_PURPOSE = "researchPurpose"
    val FIELD_AGGREGATIONS = "fieldAggregations"
    val MAX_AGGREGATIONS = "maxAggregations"
    val FROM = "from"
    val SIZE = "size"
    val SORT_FIELD = "sortField"
    val SORT_DIR = "sortDirection"

    override def write(params: LibrarySearchParams): JsValue = {
      val fields:Seq[Option[(String, JsValue)]] = Seq(
        Some(FILTERS -> params.filters.toJson),
        params.researchPurpose map {RESEARCH_PURPOSE -> _.toJson},
        Some(FIELD_AGGREGATIONS -> params.fieldAggregations.toJson),
        Some(FROM -> params.from.toJson),
        Some(SIZE -> params.size.toJson),
        params.sortField map {SORT_FIELD -> JsString(_)},
        params.sortDirection map {SORT_DIR -> JsString(_)},
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

      val researchPurposeOption = data.get(RESEARCH_PURPOSE) map (_.convertTo[ResearchPurpose])

      val sortField = data.get(SORT_FIELD) match {
        case Some(x:JsString) => Some(x.value)
        case _ => None
      }

      val sortDirection = data.get(SORT_DIR) match {
        case Some(x:JsString) => Some(x.value)
        case _ => None
      }

      LibrarySearchParams(term, filters, researchPurposeOption, aggs, from, size, sortField, sortDirection)
    }
  }

  implicit val ESInnerFieldFormat: RootJsonFormat[ESInnerField] = jsonFormat7(ESInnerField)
  implicit val ESInternalTypeFormat: RootJsonFormat[ESInternalType] = jsonFormat3(ESInternalType)
  implicit val ESNestedTypeFormat: RootJsonFormat[ESNestedType] = jsonFormat2(ESNestedType)
  implicit val ESTypeFormat: RootJsonFormat[ESType] = jsonFormat3(ESType.apply)

  implicit object impESPropertyFields extends JsonFormat[ESPropertyFields] {
    override def write(input: ESPropertyFields): JsValue = input match {
      case estype: ESType => estype.toJson
      case esinternaltype: ESInternalType => esinternaltype.toJson
      case esinnerfield: ESInnerField => esinnerfield.toJson
      case esnestedtype: ESNestedType => esnestedtype.toJson
      case _ => throw new SerializationException("unexpected ESProperty type")
    }

    override def read(json: JsValue): ESPropertyFields = {
      val data = json.asJsObject.fields
      data match {
        case x if x.contains("properties") => ESNestedTypeFormat.read(json)
        case x if x.contains("fields") => ESTypeFormat.read(json)
        case _ => ESInternalTypeFormat.read(json)
      }
    }
  }

  implicit val ESDatasetPropertiesFormat: RootJsonFormat[ESDatasetProperty] = jsonFormat1(ESDatasetProperty)

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

  // Build error about missing implicit for Spray parameter unmarshaller? Add an entry here.
  implicit val impMethod: RootJsonFormat[Method] = jsonFormat11(OrchMethodRepository.Method.apply)
  implicit val impConfiguration: RootJsonFormat[Configuration] = jsonFormat10(OrchMethodRepository.Configuration)
  implicit val impAgoraConfigurationShort: RootJsonFormat[AgoraConfigurationShort] = jsonFormat4(OrchMethodRepository.AgoraConfigurationShort)

  implicit val impUIWorkspaceResponse: RootJsonFormat[UIWorkspaceResponse] = jsonFormat6(UIWorkspaceResponse)

  //implicit val impEntity = jsonFormat5(Entity)
  implicit val impEntityCreateResult: RootJsonFormat[EntityCreateResult] = jsonFormat4(EntityCreateResult)
  implicit val impEntityCopyWithoutDestinationDefinition: RootJsonFormat[EntityCopyWithoutDestinationDefinition] = jsonFormat3(EntityCopyWithoutDestinationDefinition)
  implicit val impEntityId: RootJsonFormat[EntityId] = jsonFormat2(EntityId)

  implicit val impDestination: RootJsonFormat[MethodConfigurationId] = jsonFormat3(MethodConfigurationId)
  implicit val impMethodConfigurationCopy: RootJsonFormat[MethodConfigurationCopy] = jsonFormat4(MethodConfigurationCopy)
  implicit val impConfigurationCopyIngest: RootJsonFormat[CopyConfigurationIngest] = jsonFormat5(CopyConfigurationIngest)
  implicit val impMethodConfigurationPublish: RootJsonFormat[MethodConfigurationPublish] = jsonFormat3(MethodConfigurationPublish)
  implicit val impPublishConfigurationIngest: RootJsonFormat[PublishConfigurationIngest] = jsonFormat4(PublishConfigurationIngest)
  implicit val impMethodConfigurationName: RootJsonFormat[OrchMethodConfigurationName] = jsonFormat2(OrchMethodConfigurationName.apply)

  implicit val impFireCloudPermission: RootJsonFormat[FireCloudPermission] = jsonFormat2(FireCloudPermission)
  implicit val impAgoraPermission: RootJsonFormat[AgoraPermission] = jsonFormat2(AgoraPermission)

  implicit val impEntityAccessControl: RootJsonFormat[EntityAccessControl] = jsonFormat4(EntityAccessControl)
  implicit val impEntityAccessControlAgora: RootJsonFormat[EntityAccessControlAgora] = jsonFormat3(EntityAccessControlAgora)
  implicit val impAccessEntry: RootJsonFormat[AccessEntry] = jsonFormat4(AccessEntry)
  implicit val impPermissionReport: RootJsonFormat[PermissionReport] = jsonFormat2(PermissionReport)
  implicit val impPermissionReportRequest: RootJsonFormat[PermissionReportRequest] = jsonFormat2(PermissionReportRequest)
  implicit val impMethodAclPair: RootJsonFormat[MethodAclPair] = jsonFormat3(MethodAclPair)

  implicit val impEntityMetadata: RootJsonFormat[EntityMetadata] = jsonFormat3(EntityMetadata)
  implicit val impModelSchema: RootJsonFormat[EntityModel] = jsonFormat1(EntityModel)
  implicit val impOrchSubmissionRequest: RootJsonFormat[OrchSubmissionRequest] = jsonFormat11(OrchSubmissionRequest)

  implicit val impEntityUpdateDefinition: RootJsonFormat[EntityUpdateDefinition] = jsonFormat3(EntityUpdateDefinition)

  implicit val impFireCloudKeyValue: RootJsonFormat[FireCloudKeyValue] = jsonFormat2(FireCloudKeyValue)
  implicit val impThurloeKeyValue: RootJsonFormat[ThurloeKeyValue] = jsonFormat2(ThurloeKeyValue)
  implicit val impThurloeKeyValues: RootJsonFormat[ThurloeKeyValues] = jsonFormat2(ThurloeKeyValues)
  implicit val impBasicProfile: RootJsonFormat[BasicProfile] = jsonFormat12(BasicProfile)
  implicit val impProfile: RootJsonFormat[Profile] = jsonFormat13(Profile.apply)
  implicit val impProfileWrapper: RootJsonFormat[ProfileWrapper] = jsonFormat2(ProfileWrapper)
  implicit val impProfileKVP: RootJsonFormat[ProfileKVP] = jsonFormat2(ProfileKVP)
  implicit val impTerraPreference: RootJsonFormat[TerraPreference] = jsonFormat2(TerraPreference)
  implicit val impShibbolethToken: RootJsonFormat[ShibbolethToken] = jsonFormat2(ShibbolethToken)

  implicit val impRegisterRequest: RootJsonFormat[RegisterRequest] = jsonFormat2(RegisterRequest)
  implicit val impSamUserAttributesRequest: RootJsonFormat[SamUserAttributesRequest] = jsonFormat1(SamUserAttributesRequest)
  implicit val impSamUserRegistrationRequest: RootJsonFormat[SamUserRegistrationRequest] = jsonFormat2(SamUserRegistrationRequest)

  implicit val impJWTWrapper: RootJsonFormat[JWTWrapper] = jsonFormat1(JWTWrapper)

  implicit val impOAuthUser: RootJsonFormat[OAuthUser] = jsonFormat2(OAuthUser)

  implicit val impWorkbenchUserInfo: RootJsonFormat[WorkbenchUserInfo] = jsonFormat2(WorkbenchUserInfo)
  implicit val impWorkbenchEnabled: RootJsonFormat[WorkbenchEnabled] = jsonFormat3(WorkbenchEnabled)
  implicit val impWorkbenchEnabledV2: RootJsonFormat[WorkbenchEnabledV2] = jsonFormat3(WorkbenchEnabledV2)
  implicit val impRegistrationInfo: RootJsonFormat[RegistrationInfo] = jsonFormat3(RegistrationInfo)
  implicit val impRegistrationInfoV2: RootJsonFormat[RegistrationInfoV2] = jsonFormat3(RegistrationInfoV2)
  implicit val impSamUserResponse: RootJsonFormat[SamUserResponse] = jsonFormat8(SamUserResponse)
  implicit val impUserIdInfo: RootJsonFormat[UserIdInfo] = jsonFormat3(UserIdInfo)
  implicit val impCurator: RootJsonFormat[Curator] = jsonFormat1(Curator)
  implicit val impUserImportPermission: RootJsonFormat[UserImportPermission] = jsonFormat2(UserImportPermission)

  implicit val impPFBImportRequest: RootJsonFormat[PFBImportRequest] = jsonFormat1(PFBImportRequest)
  implicit val impOptions: RootJsonFormat[ImportOptions] = jsonFormat2(ImportOptions)
  implicit val impAsyncImportRequest: RootJsonFormat[AsyncImportRequest] = jsonFormat3(AsyncImportRequest)
  implicit val impAsyncImportResponse: RootJsonFormat[AsyncImportResponse] = jsonFormat3(AsyncImportResponse)
  implicit val impImportServiceRequest: RootJsonFormat[ImportServiceRequest] = jsonFormat4(ImportServiceRequest)
  implicit val impImportServiceResponse: RootJsonFormat[ImportServiceResponse] = jsonFormat3(ImportServiceResponse)
  implicit val impImportServiceListResponse: RootJsonFormat[ImportServiceListResponse] = jsonFormat4(ImportServiceListResponse)

  implicit val impWorkspaceStorageCostEstimate: RootJsonFormat[WorkspaceStorageCostEstimate] = jsonFormat2(WorkspaceStorageCostEstimate)

  implicit object impManagedGroupRoleFormat extends RootJsonFormat[ManagedGroupRole] {
    override def write(obj: ManagedGroupRole): JsValue = JsString(obj.toString)

    override def read(json: JsValue): ManagedGroupRole = json match {
      case JsString(name) => ManagedGroupRoles.withName(name)
      case _ => throw new DeserializationException("could not deserialize project role")
    }
  }

  implicit val impFireCloudManagedGroup: RootJsonFormat[FireCloudManagedGroup] = jsonFormat3(FireCloudManagedGroup)
  implicit val impFireCloudManagedGroupMembership: RootJsonFormat[FireCloudManagedGroupMembership] = jsonFormat3(FireCloudManagedGroupMembership)

  implicit val impResourceId: ValueObjectFormat[ResourceId] = ValueObjectFormat(ResourceId)
  implicit val impAccessPolicyName: ValueObjectFormat[AccessPolicyName] = ValueObjectFormat(AccessPolicyName)
  implicit val impUserPolicy: RootJsonFormat[UserPolicy] = jsonFormat5(UserPolicy)



  implicit val AttributeDetailFormat: RootJsonFormat[AttributeDetail] = rootFormat(lazyFormat(jsonFormat5(AttributeDetail)))
  implicit val AttributeDefinitionFormat: RootJsonFormat[AttributeDefinition] = jsonFormat1(AttributeDefinition)


  implicit val impAggregationTermResult: RootJsonFormat[AggregationTermResult] = jsonFormat2(AggregationTermResult)
  implicit val impAggregationFieldResults: RootJsonFormat[AggregationFieldResults] = jsonFormat2(AggregationFieldResults)
  implicit val impLibraryAggregationResponse: RootJsonFormat[LibraryAggregationResponse] = jsonFormat2(LibraryAggregationResponse)
  implicit val impLibrarySearchResponse: RootJsonFormat[LibrarySearchResponse] = jsonFormat4(LibrarySearchResponse)
  implicit val impLibraryBulkIndexResponse: RootJsonFormat[LibraryBulkIndexResponse] = jsonFormat3(LibraryBulkIndexResponse)

  implicit val impStructuredDataRequest: RootJsonFormat[StructuredDataRequest] = jsonFormat12(StructuredDataRequest)
  implicit val impStructuredDataResponse: RootJsonFormat[StructuredDataResponse] = jsonFormat4(StructuredDataResponse)

  implicit val impOntologyTermParent: RootJsonFormat[TermParent] = jsonFormat5(TermParent)
  implicit val impOntologyTermResource: RootJsonFormat[TermResource] = jsonFormat7(TermResource)
  implicit val impOntologyESTermParent: RootJsonFormat[ESTermParent] = jsonFormat2(ESTermParent)

  implicit val impThurloeStatus: RootJsonFormat[ThurloeStatus] = jsonFormat2(ThurloeStatus)

  // don't make this implicit! It would be pulled in by anything including ModelJsonProtocol._
  val entityExtractionRejectionHandler = RejectionHandler.newBuilder().handle {
    case MalformedRequestContentRejection(errorMsg, _) =>
      complete(BadRequest, errorMsg)
  }.result()

  // See http://stackoverflow.com/questions/24526103/generic-spray-client and
  // https://gist.github.com/mikemckibben/fad4328de85a79a06bf3
  implicit def rootEitherFormat[A : RootJsonFormat, B : RootJsonFormat]: RootJsonFormat[Either[A, B]] = new RootJsonFormat[Either[A, B]] {
    val format = DefaultJsonProtocol.eitherFormat[A, B]
    def write(either: Either[A, B]) = format.write(either)
    def read(value: JsValue) = format.read(value)
  }



  // following are horribly copied-and-pasted from rawls core, since they're not available as shared models
  implicit object ProjectStatusFormat extends RootJsonFormat[CreationStatuses.CreationStatus] {
    override def write(obj: CreationStatuses.CreationStatus): JsValue = JsString(obj.toString)

    override def read(json: JsValue): CreationStatuses.CreationStatus = json match {
      case JsString(name) => CreationStatuses.withName(name)
      case _ => throw new DeserializationException("could not deserialize project status")
    }
  }

  implicit object ProjectRoleFormat extends RootJsonFormat[ProjectRole] {
    override def write(obj: ProjectRole): JsValue = JsString(obj.toString)

    override def read(json: JsValue): ProjectRole = json match {
      case JsString(name) => ProjectRoles.withName(name)
      case _ => throw new DeserializationException("could not deserialize project role")
    }
  }

  implicit val impRawlsBillingProjectMember: RootJsonFormat[RawlsBillingProjectMember] = jsonFormat2(RawlsBillingProjectMember)

  // END copy/paste from rawls

  implicit val impRawlsBillingProjectMembership: RootJsonFormat[RawlsBillingProjectMembership] = jsonFormat4(RawlsBillingProjectMembership)

  implicit val impCreateRawlsBillingProjectFullRequestFormat: RootJsonFormat[CreateRawlsBillingProjectFullRequest] = jsonFormat2(CreateRawlsBillingProjectFullRequest)

  implicit object ShareTypeFormat extends RootJsonFormat[ShareType.Value] {
    override def write(obj: ShareType.Value): JsValue = JsString(obj.toString)

    override def read(json: JsValue): ShareType.Value = json match {
      case JsString(name) => ShareType.withName(name)
      case _ => throw DeserializationException("could not deserialize share type")
    }
  }

  implicit val impShareFormat: RootJsonFormat[Share] = jsonFormat4(Share)
}
