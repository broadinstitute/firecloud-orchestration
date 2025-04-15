package org.broadinstitute.dsde.firecloud.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedRequestContentRejection, RejectionHandler}
import org.broadinstitute.dsde.firecloud.model.ManagedGroupRoles.ManagedGroupRole
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository._
import org.broadinstitute.dsde.firecloud.model.Project.ProjectRoles.ProjectRole
import org.broadinstitute.dsde.firecloud.model.Project._
import org.broadinstitute.dsde.firecloud.model.SamResource.{AccessPolicyName, ResourceId, UserPolicy}
import org.broadinstitute.dsde.firecloud.utils.StatusCodeUtils
import org.broadinstitute.dsde.rawls.model.UserModelJsonSupport._
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport.WorkspaceAccessLevelFormat
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.workbench.model.ValueObjectFormat
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport.InstantFormat
import spray.json._

//noinspection TypeAnnotation,RedundantNewCaseClass
object ModelJsonProtocol extends WorkspaceJsonSupport with SprayJsonSupport with StatusCodeUtils {
  import spray.json.DefaultJsonProtocol._

  implicit object impStatusCode extends JsonFormat[StatusCode] {
    override def write(code: StatusCode): JsValue = JsNumber(code.intValue)

    override def read(json: JsValue): StatusCode = json match {
      case JsNumber(n) => statusCodeFrom(n.intValue)
      case _           => throw DeserializationException("unexpected json type")
    }
  }

  implicit object impStackTraceElement extends RootJsonFormat[StackTraceElement] {
    val CLASS_NAME = "className"
    val METHOD_NAME = "methodName"
    val FILE_NAME = "fileName"
    val LINE_NUMBER = "lineNumber"

    def write(stackTraceElement: StackTraceElement) =
      JsObject(
        CLASS_NAME -> JsString(stackTraceElement.getClassName),
        METHOD_NAME -> JsString(stackTraceElement.getMethodName),
        FILE_NAME -> JsString(stackTraceElement.getFileName),
        LINE_NUMBER -> JsNumber(stackTraceElement.getLineNumber)
      )

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
  implicit val impAgoraConfigurationShort: RootJsonFormat[AgoraConfigurationShort] = jsonFormat4(
    OrchMethodRepository.AgoraConfigurationShort
  )

  implicit val impUIWorkspaceResponse: RootJsonFormat[UIWorkspaceResponse] = jsonFormat6(UIWorkspaceResponse)

  // implicit val impEntity = jsonFormat5(Entity)
  implicit val impEntityCreateResult: RootJsonFormat[EntityCreateResult] = jsonFormat4(EntityCreateResult)
  implicit val impEntityCopyWithoutDestinationDefinition: RootJsonFormat[EntityCopyWithoutDestinationDefinition] =
    jsonFormat3(EntityCopyWithoutDestinationDefinition)
  implicit val impEntityId: RootJsonFormat[EntityId] = jsonFormat2(EntityId)

  implicit val impDestination: RootJsonFormat[MethodConfigurationId] = jsonFormat3(MethodConfigurationId)
  implicit val impMethodConfigurationCopy: RootJsonFormat[MethodConfigurationCopy] = jsonFormat4(
    MethodConfigurationCopy
  )
  implicit val impConfigurationCopyIngest: RootJsonFormat[CopyConfigurationIngest] = jsonFormat5(
    CopyConfigurationIngest
  )
  implicit val impMethodConfigurationPublish: RootJsonFormat[MethodConfigurationPublish] = jsonFormat3(
    MethodConfigurationPublish
  )
  implicit val impPublishConfigurationIngest: RootJsonFormat[PublishConfigurationIngest] = jsonFormat4(
    PublishConfigurationIngest
  )
  implicit val impMethodConfigurationName: RootJsonFormat[OrchMethodConfigurationName] = jsonFormat2(
    OrchMethodConfigurationName.apply
  )

  implicit val impFireCloudPermission: RootJsonFormat[FireCloudPermission] = jsonFormat2(FireCloudPermission)
  implicit val impAgoraPermission: RootJsonFormat[AgoraPermission] = jsonFormat2(AgoraPermission)

  implicit val impEntityAccessControl: RootJsonFormat[EntityAccessControl] = jsonFormat4(EntityAccessControl)
  implicit val impEntityAccessControlAgora: RootJsonFormat[EntityAccessControlAgora] = jsonFormat3(
    EntityAccessControlAgora
  )
  implicit val impAccessEntry: RootJsonFormat[AccessEntry] = jsonFormat4(AccessEntry)
  implicit val impPermissionReport: RootJsonFormat[PermissionReport] = jsonFormat2(PermissionReport)
  implicit val impPermissionReportRequest: RootJsonFormat[PermissionReportRequest] = jsonFormat2(
    PermissionReportRequest
  )
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
  implicit val impSamUserAttributesRequest: RootJsonFormat[SamUserAttributesRequest] = jsonFormat1(
    SamUserAttributesRequest
  )
  implicit val impSamUserRegistrationRequest: RootJsonFormat[SamUserRegistrationRequest] = jsonFormat2(
    SamUserRegistrationRequest
  )

  implicit val impJWTWrapper: RootJsonFormat[JWTWrapper] = jsonFormat1(JWTWrapper)

  implicit val impOAuthUser: RootJsonFormat[OAuthUser] = jsonFormat2(OAuthUser)

  implicit val impWorkbenchUserInfo: RootJsonFormat[WorkbenchUserInfo] = jsonFormat2(WorkbenchUserInfo)
  implicit val impWorkbenchEnabled: RootJsonFormat[WorkbenchEnabled] = jsonFormat3(WorkbenchEnabled)
  implicit val impWorkbenchEnabledV2: RootJsonFormat[WorkbenchEnabledV2] = jsonFormat3(WorkbenchEnabledV2)
  implicit val impRegistrationInfo: RootJsonFormat[RegistrationInfo] = jsonFormat3(RegistrationInfo)
  implicit val impRegistrationInfoV2: RootJsonFormat[RegistrationInfoV2] = jsonFormat3(RegistrationInfoV2)
  implicit val impSamUserResponse: RootJsonFormat[SamUserResponse] = jsonFormat8(SamUserResponse)
  implicit val impSamUser: RootJsonFormat[SamUser] = jsonFormat8(SamUser)
  implicit val impUserIdInfo: RootJsonFormat[UserIdInfo] = jsonFormat3(UserIdInfo)
  implicit val impUserImportPermission: RootJsonFormat[UserImportPermission] = jsonFormat2(UserImportPermission)

  implicit val impPFBImportRequest: RootJsonFormat[PFBImportRequest] = jsonFormat1(PFBImportRequest)
  implicit val impOptions: RootJsonFormat[ImportOptions] = jsonFormat2(ImportOptions)
  implicit val impAsyncImportRequest: RootJsonFormat[AsyncImportRequest] = jsonFormat3(AsyncImportRequest)
  implicit val impAsyncImportResponse: RootJsonFormat[AsyncImportResponse] = jsonFormat3(AsyncImportResponse)
  implicit val impCwdsResponse: RootJsonFormat[CwdsResponse] = jsonFormat3(CwdsResponse)
  implicit val impCwdsListResponse: RootJsonFormat[CwdsListResponse] = jsonFormat4(CwdsListResponse)

  implicit val impWorkspaceStorageUsageAndCostEstimate: RootJsonFormat[WorkspaceStorageUsageAndCostEstimate] =
    jsonFormat3(
      WorkspaceStorageUsageAndCostEstimate
    )

  implicit object impManagedGroupRoleFormat extends RootJsonFormat[ManagedGroupRole] {
    override def write(obj: ManagedGroupRole): JsValue = JsString(obj.toString)

    override def read(json: JsValue): ManagedGroupRole = json match {
      case JsString(name) => ManagedGroupRoles.withName(name)
      case _              => throw new DeserializationException("could not deserialize project role")
    }
  }

  implicit val impFireCloudManagedGroup: RootJsonFormat[FireCloudManagedGroup] = jsonFormat3(FireCloudManagedGroup)
  implicit val impFireCloudManagedGroupMembership: RootJsonFormat[FireCloudManagedGroupMembership] = jsonFormat3(
    FireCloudManagedGroupMembership
  )

  implicit val impResourceId: ValueObjectFormat[ResourceId] = ValueObjectFormat(ResourceId)
  implicit val impAccessPolicyName: ValueObjectFormat[AccessPolicyName] = ValueObjectFormat(AccessPolicyName)
  implicit val impUserPolicy: RootJsonFormat[UserPolicy] = jsonFormat5(UserPolicy)

  implicit val impThurloeStatus: RootJsonFormat[ThurloeStatus] = jsonFormat2(ThurloeStatus)

  // don't make this implicit! It would be pulled in by anything including ModelJsonProtocol._
  val entityExtractionRejectionHandler = RejectionHandler
    .newBuilder()
    .handle { case MalformedRequestContentRejection(errorMsg, _) =>
      complete(BadRequest, errorMsg)
    }
    .result()

  // See http://stackoverflow.com/questions/24526103/generic-spray-client and
  // https://gist.github.com/mikemckibben/fad4328de85a79a06bf3
  implicit def rootEitherFormat[A: RootJsonFormat, B: RootJsonFormat]: RootJsonFormat[Either[A, B]] =
    new RootJsonFormat[Either[A, B]] {
      val format = DefaultJsonProtocol.eitherFormat[A, B]
      def write(either: Either[A, B]) = format.write(either)
      def read(value: JsValue) = format.read(value)
    }

  // following are horribly copied-and-pasted from rawls core, since they're not available as shared models
  implicit object ProjectStatusFormat extends RootJsonFormat[CreationStatuses.CreationStatus] {
    override def write(obj: CreationStatuses.CreationStatus): JsValue = JsString(obj.toString)

    override def read(json: JsValue): CreationStatuses.CreationStatus = json match {
      case JsString(name) => CreationStatuses.withName(name)
      case _              => throw new DeserializationException("could not deserialize project status")
    }
  }

  implicit object ProjectRoleFormat extends RootJsonFormat[ProjectRole] {
    override def write(obj: ProjectRole): JsValue = JsString(obj.toString)

    override def read(json: JsValue): ProjectRole = json match {
      case JsString(name) => ProjectRoles.withName(name)
      case _              => throw new DeserializationException("could not deserialize project role")
    }
  }

  implicit val impRawlsBillingProjectMember: RootJsonFormat[RawlsBillingProjectMember] = jsonFormat2(
    RawlsBillingProjectMember
  )

  // END copy/paste from rawls

  implicit val impRawlsBillingProjectMembership: RootJsonFormat[RawlsBillingProjectMembership] = jsonFormat4(
    RawlsBillingProjectMembership
  )

  implicit val impCreateRawlsBillingProjectFullRequestFormat: RootJsonFormat[CreateRawlsBillingProjectFullRequest] =
    jsonFormat2(CreateRawlsBillingProjectFullRequest)

  implicit val impWorkspaceIdFormat: RootJsonFormat[WorkspaceId] = jsonFormat1(WorkspaceId)
  implicit val impWorkspaceIdResponseFormat: RootJsonFormat[WorkspaceIdResponse] = jsonFormat1(WorkspaceIdResponse)

}
