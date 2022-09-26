package org.broadinstitute.dsde.firecloud.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedRequestContentRejection, RejectionHandler}
import org.broadinstitute.dsde.firecloud.model.DUOS._
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
import spray.json.{JsString, _}

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
  implicit val impResearchPurpose = jsonFormat6(ResearchPurpose.apply)
  implicit val impResearchPurposeRequest = jsonFormat7(ResearchPurposeRequest.apply)

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

  implicit val ESInnerFieldFormat = jsonFormat7(ESInnerField)
  implicit val ESInternalTypeFormat = jsonFormat3(ESInternalType)
  implicit val ESNestedTypeFormat = jsonFormat2(ESNestedType)
  implicit val ESTypeFormat = jsonFormat3(ESType.apply)

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

  implicit val ESDatasetPropertiesFormat = jsonFormat1(ESDatasetProperty)

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
  implicit val impMethod = jsonFormat11(OrchMethodRepository.Method.apply)
  implicit val impConfiguration = jsonFormat10(OrchMethodRepository.Configuration)
  implicit val impAgoraConfigurationShort = jsonFormat4(OrchMethodRepository.AgoraConfigurationShort)

  implicit val impUIWorkspaceResponse = jsonFormat6(UIWorkspaceResponse)

  //implicit val impEntity = jsonFormat5(Entity)
  implicit val impEntityCreateResult = jsonFormat4(EntityCreateResult)
  implicit val impEntityCopyWithoutDestinationDefinition = jsonFormat3(EntityCopyWithoutDestinationDefinition)
  implicit val impEntityId = jsonFormat2(EntityId)

  implicit val impDestination = jsonFormat3(MethodConfigurationId)
  implicit val impMethodConfigurationCopy = jsonFormat4(MethodConfigurationCopy)
  implicit val impConfigurationCopyIngest = jsonFormat5(CopyConfigurationIngest)
  implicit val impMethodConfigurationPublish = jsonFormat3(MethodConfigurationPublish)
  implicit val impPublishConfigurationIngest = jsonFormat4(PublishConfigurationIngest)
  implicit val impMethodConfigurationName = jsonFormat2(OrchMethodConfigurationName.apply)

  implicit val impFireCloudPermission = jsonFormat2(FireCloudPermission)
  implicit val impAgoraPermission = jsonFormat2(AgoraPermission)

  implicit val impEntityAccessControl = jsonFormat4(EntityAccessControl)
  implicit val impEntityAccessControlAgora = jsonFormat3(EntityAccessControlAgora)
  implicit val impAccessEntry = jsonFormat4(AccessEntry)
  implicit val impPermissionReport = jsonFormat2(PermissionReport)
  implicit val impPermissionReportRequest = jsonFormat2(PermissionReportRequest)
  implicit val impMethodAclPair = jsonFormat3(MethodAclPair)

  implicit val impEntityMetadata = jsonFormat3(EntityMetadata)
  implicit val impModelSchema = jsonFormat1(EntityModel)
  implicit val impOrchSubmissionRequest = jsonFormat11(OrchSubmissionRequest)

  implicit val impEntityUpdateDefinition = jsonFormat3(EntityUpdateDefinition)

  implicit val impFireCloudKeyValue = jsonFormat2(FireCloudKeyValue)
  implicit val impThurloeKeyValue = jsonFormat2(ThurloeKeyValue)
  implicit val impThurloeKeyValues = jsonFormat2(ThurloeKeyValues)
  implicit val impBasicProfile = jsonFormat10(BasicProfile)
  implicit val impProfile = jsonFormat11(Profile.apply)
  implicit val impProfileWrapper = jsonFormat2(ProfileWrapper)
  implicit val impProfileKVP = jsonFormat2(ProfileKVP)
  implicit val impTerraPreference = jsonFormat2(TerraPreference)
  implicit val impShibbolethToken = jsonFormat2(ShibbolethToken)

  implicit val impJWTWrapper = jsonFormat1(JWTWrapper)

  implicit val impOAuthUser = jsonFormat2(OAuthUser)

  implicit val impWorkbenchUserInfo = jsonFormat2(WorkbenchUserInfo)
  implicit val impWorkbenchEnabled = jsonFormat3(WorkbenchEnabled)
  implicit val impWorkbenchEnabledV2 = jsonFormat3(WorkbenchEnabledV2)
  implicit val impRegistrationInfo = jsonFormat3(RegistrationInfo)
  implicit val impRegistrationInfoV2 = jsonFormat3(RegistrationInfoV2)
  implicit val impUserIdInfo = jsonFormat3(UserIdInfo)
  implicit val impCurator = jsonFormat1(Curator)
  implicit val impUserImportPermission = jsonFormat2(UserImportPermission)

  implicit val impBagitImportRequest = jsonFormat2(BagitImportRequest)
  implicit val impPFBImportRequest = jsonFormat1(PFBImportRequest)
  implicit val impOptions = jsonFormat1(ImportOptions)
  implicit val impAsyncImportRequest = jsonFormat3(AsyncImportRequest)
  implicit val impAsyncImportResponse = jsonFormat3(AsyncImportResponse)
  implicit val impImportServiceRequest = jsonFormat4(ImportServiceRequest)
  implicit val impImportServiceResponse = jsonFormat3(ImportServiceResponse)

  implicit val impWorkspaceStorageCostEstimate = jsonFormat2(WorkspaceStorageCostEstimate)

  implicit val impGoogleObjectMetadata = jsonFormat16(ObjectMetadata)

  implicit object impManagedGroupRoleFormat extends RootJsonFormat[ManagedGroupRole] {
    override def write(obj: ManagedGroupRole): JsValue = JsString(obj.toString)

    override def read(json: JsValue): ManagedGroupRole = json match {
      case JsString(name) => ManagedGroupRoles.withName(name)
      case _ => throw new DeserializationException("could not deserialize project role")
    }
  }

  implicit val impFireCloudManagedGroup = jsonFormat3(FireCloudManagedGroup)
  implicit val impFireCloudManagedGroupMembership = jsonFormat3(FireCloudManagedGroupMembership)

  implicit val impResourceId = ValueObjectFormat(ResourceId)
  implicit val impAccessPolicyName = ValueObjectFormat(AccessPolicyName)
  implicit val impUserPolicy = jsonFormat5(UserPolicy)



  implicit val AttributeDetailFormat: RootJsonFormat[AttributeDetail] = rootFormat(lazyFormat(jsonFormat5(AttributeDetail)))
  implicit val AttributeDefinitionFormat = jsonFormat1(AttributeDefinition)


  implicit val impAggregationTermResult = jsonFormat2(AggregationTermResult)
  implicit val impAggregationFieldResults = jsonFormat2(AggregationFieldResults)
  implicit val impLibraryAggregationResponse = jsonFormat2(LibraryAggregationResponse)
  implicit val impLibrarySearchResponse = jsonFormat4(LibrarySearchResponse)
  implicit val impLibraryBulkIndexResponse = jsonFormat3(LibraryBulkIndexResponse)

  implicit val impStructuredDataRequest = jsonFormat12(StructuredDataRequest)
  implicit val impStructuredDataResponse = jsonFormat4(StructuredDataResponse)

  implicit object impDuosDataUse extends RootJsonFormat[DuosDataUse] {
    override def write(ddu: DuosDataUse): JsValue = {
      val existingProps: Seq[(String, JsValue)] = Try(ddu.getClass.getDeclaredFields.map { f =>
        f.setAccessible(true)
        f.get(ddu) match {
          case Some(x: Boolean) => f.getName -> x.toJson
          case Some(y: String) => f.getName -> y.toJson
          case Some((h: String) :: tail) => f.getName -> (h +: tail.collect { case z: String => z }).toJson
          case _ => f.getName -> JsNull
        }
      }) match {
        case Success(props) => props.toIndexedSeq.filterNot(_._2 == JsNull)
        case Failure(ex) => serializationError(ex.getMessage)
      }
      JsObject(existingProps.toMap)
    }
    override def read(json: JsValue): DuosDataUse = {
      Try(DuosDataUse.apply(json.asJsObject.fields)) match {
        case Success(ddu) => ddu
        case Failure(ex) => deserializationError(s"Could not read DuosDataUse value: $json", ex)
      }
    }
  }
  implicit val impDuosConsent = jsonFormat11(Consent)
  implicit val impDuosConsentError = jsonFormat2(ConsentError)
  implicit val impOntologyTermParent = jsonFormat5(TermParent)
  implicit val impOntologyTermResource = jsonFormat7(TermResource)
  implicit val impOntologyESTermParent = jsonFormat2(ESTermParent)

  implicit val impThurloeStatus = jsonFormat2(ThurloeStatus)
  implicit val impDropwizardHealth = jsonFormat2(DropwizardHealth)
  implicit val impDuosConsentStatus = jsonFormat3(ConsentStatus)

  // don't make this implicit! It would be pulled in by anything including ModelJsonProtocol._
  val entityExtractionRejectionHandler = RejectionHandler.newBuilder().handle {
    case MalformedRequestContentRejection(errorMsg, _) =>
      complete(BadRequest, errorMsg)
  }.result()

  // See http://stackoverflow.com/questions/24526103/generic-spray-client and
  // https://gist.github.com/mikemckibben/fad4328de85a79a06bf3
  implicit def rootEitherFormat[A : RootJsonFormat, B : RootJsonFormat] = new RootJsonFormat[Either[A, B]] {
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

  implicit val impRawlsBillingProjectMember = jsonFormat2(RawlsBillingProjectMember)

  // END copy/paste from rawls

  implicit val impRawlsBillingProjectMembership = jsonFormat4(RawlsBillingProjectMembership)

  implicit val impCreateRawlsBillingProjectFullRequestFormat = jsonFormat2(CreateRawlsBillingProjectFullRequest)

  implicit object ShareTypeFormat extends RootJsonFormat[ShareType.Value] {
    override def write(obj: ShareType.Value): JsValue = JsString(obj.toString)

    override def read(json: JsValue): ShareType.Value = json match {
      case JsString(name) => ShareType.withName(name)
      case _ => throw DeserializationException("could not deserialize share type")
    }
  }

  implicit val impShareFormat = jsonFormat4(Share)
}
