package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{LibraryService, WorkspaceApiServiceSpec}
import org.broadinstitute.dsde.rawls.model.{StatusCheckResponse => RawlsStatus, SubsystemStatus => RawlsSubsystemStatus, _}
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.joda.time.DateTime
import spray.http.StatusCodes
import MockRawlsDAO._
import org.broadinstitute.dsde.firecloud.model.Trial.RawlsBillingProjectMember
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

// Common things that can be accessed from tests
object MockRawlsDAO {

  val sampleAtts: Map[AttributeName, AttributeListElementable with Product with Serializable] = {
    Map(
      AttributeName.withDefaultNS("sample_type") -> AttributeString("Blood"),
      AttributeName.withDefaultNS("header_1") -> AttributeString(MockUtils.randomAlpha()),
      AttributeName.withDefaultNS("header_2") -> AttributeString(MockUtils.randomAlpha()),
      AttributeName.withDefaultNS("participant_id") -> AttributeEntityReference("participant", "participant_name")
    )
  }

  val validSampleEntities = List(
    Entity("sample_01", "sample", sampleAtts),
    Entity("sample_02", "sample", sampleAtts),
    Entity("sample_03", "sample", sampleAtts),
    Entity("sample_04", "sample", sampleAtts)
  )

  val validEntitiesMetadata = Map(
    "participant" -> EntityTypeMetadata(count = 1, idName = "participant_id", attributeNames = List("age", "gender", "cohort")),
    "sample" -> EntityTypeMetadata(count = validSampleEntities.size, idName = "sample_id", attributeNames = sampleAtts.map(_._1.name).toList),
    "sample_set" -> EntityTypeMetadata(count = 1, idName = "sample_set_id", attributeNames = List("samples"))
  )

  // Large Sample Data

  val largeSampleSize = 20000

  val largeSampleHeaders: Seq[AttributeName] = (1 to 150).map { h => AttributeName.withDefaultNS(s"prop_$h") }

  val largeSampleAttributes: Map[AttributeName, AttributeString] = {
    largeSampleHeaders.map { h => Map(h -> AttributeString(MockUtils.randomAlpha()))}.reduce(_ ++ _)
  }

  val paginatedEntityRangeLimit = FireCloudConfig.Rawls.defaultPageSize - 1
  def generateSamplesInRange(from: Int): List[Entity] = (from to from + paginatedEntityRangeLimit).map { pos => Entity(s"sample_0$pos", "sample", largeSampleAttributes) }.toList

  val largeSampleMetadata = Map(
    "sample" -> EntityTypeMetadata(
      count = largeSampleSize,
      idName = "sample_id",
      attributeNames = largeSampleHeaders.map(_.name))
  )

  // Large Sample Set Data

  val largeSampleSetSize = 5000

  // Same as the large sample headers, except we can drop the last one because we're adding the samples membership attribute.
  val largeSampleSetHeaders: Seq[AttributeName] = largeSampleHeaders.reverse.tail

  // Give each sample set a set of 100 samples. That gives us 500K entities to process.
  val largeSampleSetSamples = AttributeEntityReferenceList(
    (1 to 100).map { i => AttributeEntityReference(entityType = "sample", entityName = s"sample_0$i") }
  )
  val largeSampleSetAttributes: Map[AttributeName, Attribute] = {
    Map(AttributeName.withDefaultNS("samples") -> largeSampleSetSamples) ++
    largeSampleSetHeaders.map { h => Map(h -> AttributeString(MockUtils.randomAlpha()))}.reduce(_ ++ _)
  }

  def generateSampleSetsInRange(from: Int): List[Entity] = (from to from + paginatedEntityRangeLimit).map { pos => Entity(s"sample_set_0$pos", "sample_set", largeSampleSetAttributes) }.toList

  val largeSampleSetMetadata = Map(
    "sample_set" -> EntityTypeMetadata(
      count = largeSampleSetSize,
      idName = "sample_set_id",
      attributeNames = largeSampleSetAttributes.map(_._1.name).toSeq))

}

/**
  * Created by davidan on 9/28/16.
  *
  */
class MockRawlsDAO  extends RawlsDAO {

  var groups: Map[String, Set[String]] = Map(
    "TCGA-dbGaP-Authorized" -> Set("tcga-linked", "tcga-linked-no-expire-date", "tcga-linked-expired", "tcga-linked-user-invalid-expire-date", "tcga-and-target-linked", "tcga-and-target-linked-expired"),
    "TARGET-dbGaP-Authorized" -> Set("target-linked", "target-linked-expired", "tcga-and-target-linked", "tcga-and-target-linked-expired")
  )

  private val rawlsWorkspaceWithAttributes = Workspace(
    "attributes",
    "att",
    Set.empty, //authdomain
    "id",
    "", //bucketname
    DateTime.now(),
    DateTime.now(),
    "ansingh",
    Map(AttributeName("default", "a") -> AttributeBoolean(true),
      AttributeName("default", "b") -> AttributeNumber(1.23),
      AttributeName("default", "c") -> AttributeString(""),
      AttributeName("default", "d") -> AttributeString("escape quo\"te"),
      AttributeName("default", "e") -> AttributeString("v1"),
      AttributeName("default", "f") -> AttributeValueList(Seq(
        AttributeString("v6"),
        AttributeNumber(999),
        AttributeBoolean(true)
      ))),
    Map(), //acls
    Map(), //authdomain acls
    false
  )

  val publishedRawlsWorkspaceWithAttributes = Workspace(
    "attributes",
    "att",
    Set.empty, //authdomain
    "id",
    "", //bucketname
    DateTime.now(),
    DateTime.now(),
    "ansingh",
    Map(AttributeName("default", "a") -> AttributeBoolean(true),
      AttributeName("default", "b") -> AttributeNumber(1.23),
      AttributeName("default", "c") -> AttributeString(""),
      AttributeName("library", "published") -> AttributeBoolean(true),
      AttributeName("library", "projectName") -> AttributeString("testing"),
      AttributeName("default", "d") -> AttributeString("escape quo\"te"),
      AttributeName("default", "e") -> AttributeString("v1"),
      AttributeName("default", "f") -> AttributeValueList(Seq(
        AttributeString("v6"),
        AttributeNumber(999),
        AttributeBoolean(true)
      ))),
    Map(), //acls
    Map(), //authdomain acls,
    false
  )

  val unpublishedRawlsWorkspaceLibraryValid = Workspace(
    "attributes",
    "att",
    Set.empty, //realm
    "id",
    "", //bucketname
    DateTime.now(),
    DateTime.now(),
    "ansingh",
    Map(
      AttributeName.withLibraryNS("datasetName") -> AttributeString("name"),
      AttributeName.withLibraryNS("datasetVersion") -> AttributeString("v1.0"),
      AttributeName.withLibraryNS("datasetDescription") -> AttributeString("desc"),
      AttributeName.withLibraryNS("datasetCustodian") -> AttributeString("cust"),
      AttributeName.withLibraryNS("datasetDepositor") -> AttributeString("depo"),
      AttributeName.withLibraryNS("contactEmail") -> AttributeString("name@example.com"),
      AttributeName.withLibraryNS("datasetOwner") -> AttributeString("owner"),
      AttributeName.withLibraryNS("institute") -> AttributeValueList(Seq( AttributeString("inst"),AttributeString("it"),AttributeString("ute") )),
      AttributeName.withLibraryNS("indication") -> AttributeString("indic"),
      AttributeName.withLibraryNS("numSubjects") -> AttributeNumber(123),
      AttributeName.withLibraryNS("projectName") -> AttributeString("proj"),
      AttributeName.withLibraryNS("datatype") -> AttributeValueList(Seq( AttributeString("data"),AttributeString("type") )),
      AttributeName.withLibraryNS("dataCategory") -> AttributeValueList(Seq( AttributeString("data"),AttributeString("category") )),
      AttributeName.withLibraryNS("dataUseRestriction") -> AttributeString("dur"),
      AttributeName.withLibraryNS("studyDesign") -> AttributeString("study"),
      AttributeName.withLibraryNS("cellType") -> AttributeString("cell"),
      AttributeName.withLibraryNS("requiresExternalApproval") -> AttributeBoolean(false),
      AttributeName.withLibraryNS("useLimitationOption") -> AttributeString("orsp"),
      AttributeName.withLibraryNS("technology") -> AttributeValueList(Seq( AttributeString("is an optional"),AttributeString("array attribute") )),
      AttributeName.withLibraryNS("orsp") -> AttributeString("some orsp"),
      LibraryService.discoverableWSAttribute -> AttributeValueList(Seq( AttributeString("group1"),AttributeString("group2") ))
    ),
    Map(), //acls
    Map(), //realm acls,
    false
  )

  val rawlsWorkspaceResponseWithAttributes = WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare=false, canCompute=true, catalog=false, rawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)
  val publishedRawlsWorkspaceResponseWithAttributes = WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare=false, canCompute=true, catalog=false, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)

  def newWorkspace: Workspace = {
    Workspace(
      namespace = "namespace",
      name = "name",
      authorizationDomain = Set.empty,
      workspaceId = "workspaceId",
      bucketName = "bucketName",
      createdDate = DateTime.now(),
      lastModified = DateTime.now(),
      createdBy = "createdBy",
      attributes = Map(),
      accessLevels = Map(),
      authDomainACLs = Map(),
      isLocked = true
    )
  }
  
  override def isAdmin(userInfo: UserInfo): Future[Boolean] = Future.successful(false)

  override def isGroupMember(userInfo: UserInfo, groupName: String): Future[Boolean] = Future.successful(true)

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = {
    Future.successful(userInfo.id == "curator")
  }

  override def registerUser(userInfo: UserInfo): Future[Unit] = Future.successful(())

  override def adminAddMemberToGroup(groupName: String, memberList: RawlsGroupMemberList): Future[Boolean] = {
    val userEmailsToAdd = memberList.userSubjectIds.getOrElse(Seq[String]()).toSet
    val groupWithNewMembers = (groupName -> ((groups(groupName).filterNot(userEmailsToAdd.contains)) ++ userEmailsToAdd))
    this.synchronized { groups = groups + groupWithNewMembers }

    Future.successful(true)
  }

  override def adminOverwriteGroupMembership(groupName: String, memberList: RawlsGroupMemberList): Future[Boolean] = {
    val userEmailsToAdd = memberList.userSubjectIds.getOrElse(Set[String]()).toSet
    val groupWithNewMembers = (groupName -> userEmailsToAdd)
    this.synchronized { groups = groups + groupWithNewMembers }

    Future.successful(true)
  }

  override def getGroupsForUser(implicit userToken: WithAccessToken): Future[Seq[String]] = {
    Future.successful(Seq("TestUserGroup"))
  }

  override def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse] = {
    Future.successful(BucketUsageResponse(BigInt("256000000000")))
  }

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] = {
    ns match {
      case "projectowner" => Future(WorkspaceResponse(WorkspaceAccessLevels.ProjectOwner, canShare = true, canCompute=true, catalog=false, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "reader" => Future(WorkspaceResponse(WorkspaceAccessLevels.Read, canShare = false, canCompute=true, catalog=false, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "attributes" => Future(rawlsWorkspaceResponseWithAttributes)
      case "publishedreader" => Future(WorkspaceResponse(WorkspaceAccessLevels.Read, canShare = false, canCompute=true, catalog=false, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "publishedreadercatalog" => Future(WorkspaceResponse(WorkspaceAccessLevels.Read, canShare = false, canCompute=true, catalog=true, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "publishedwriter" => Future(WorkspaceResponse(WorkspaceAccessLevels.Write, canShare = false, canCompute=true, catalog=false, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "unpublishedwriter" => Future(WorkspaceResponse(WorkspaceAccessLevels.Write, canShare = false, canCompute=true, catalog=false, rawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "publishedowner" => Future.successful(WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare = true, canCompute=true, catalog=false, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "libraryValid" => Future.successful(WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare = true, canCompute=true, catalog=false, unpublishedRawlsWorkspaceLibraryValid, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case _ => Future.successful(WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare = true, canCompute=true, catalog=false, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
    }
  }

  override def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]] = {
    Future.successful(Seq(WorkspaceListResponse(WorkspaceAccessLevels.ProjectOwner, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty, Some(false)),
      WorkspaceListResponse(WorkspaceAccessLevels.Read, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty, Some(false)),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, rawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty, Some(false)),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty, Some(false)),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty, Some(false))))
  }

  override def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace] = {
    if (name == WorkspaceApiServiceSpec.publishedWorkspace.name) {
      Future.successful(publishedRawlsWorkspaceWithAttributes)
    } else {
      Future.successful(newWorkspace)
    }
  }

  override def updateLibraryAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace] = {
    Future.successful((newWorkspace))
  }

  override def getAllLibraryPublishedWorkspaces: Future[Seq[Workspace]] = Future.successful(Seq.empty[Workspace])

  override def getWorkspaceACL(ns: String, name: String)(implicit userToken: WithAccessToken) =
    Future.successful(WorkspaceACL(Map.empty[String, AccessEntry]))

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate], inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList] = {
    Future.successful(WorkspaceACLUpdateResponseList(aclUpdates.map(update => WorkspaceACLUpdateResponse(update.email, update.accessLevel)), aclUpdates, aclUpdates, aclUpdates))
  }

  override def getRefreshTokenStatus(userInfo: UserInfo): Future[Option[DateTime]] = {
    Future.successful(None)
  }

  override def saveRefreshToken(userInfo: UserInfo, refreshToken: String): Future[Unit] = {
    Future.successful(())
  }

  override def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[Entity]] = {
    if (workspaceName == "invalid") {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "Workspace not found")))
    } else {
      Future.successful(Seq.empty)
    }
  }

  override def queryEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery)(implicit userToken: UserInfo): Future[EntityQueryResponse] = {
    if (workspaceName == "exception") {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Exception getting workspace")))
    } else if (workspaceName == "page3exception" && query.page == 3) {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Exception querying for entities on page ${query.page}")))
    } else if (workspaceName == "invalid") {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "Workspace not found")))
    } else if (workspaceName == "largeSampleSet") {
      val sampleSetRange = generateSampleSetsInRange(query.page * query.pageSize)
      val queryResponse: EntityQueryResponse = EntityQueryResponse(
        parameters = query,
        resultMetadata = EntityQueryResultMetadata(unfilteredCount = largeSampleSetSize, filteredCount = largeSampleSetSize, filteredPageCount = largeSampleSetSize/query.pageSize),
        results = sampleSetRange
      )
      Future.successful(queryResponse)
    } else if (workspaceName == "large" || workspaceName == "page3exception") {
      val sampleRange = generateSamplesInRange(query.page * query.pageSize)
      val queryResponse: EntityQueryResponse = EntityQueryResponse(
        parameters = query,
        resultMetadata = EntityQueryResultMetadata(unfilteredCount = largeSampleSize, filteredCount = largeSampleSize, filteredPageCount = largeSampleSize/query.pageSize),
        results = sampleRange
      )
      Future.successful(queryResponse)
    } else {
      val queryResponse: EntityQueryResponse = EntityQueryResponse(
        parameters = query,
        resultMetadata = EntityQueryResultMetadata(unfilteredCount = validSampleEntities.size, filteredCount = validSampleEntities.size, filteredPageCount = 1),
        results = validSampleEntities
      )
      Future.successful(queryResponse)
    }
  }

  override def getEntityTypes(workspaceNamespace: String, workspaceName: String)(implicit userToken: UserInfo): Future[Map[String, EntityTypeMetadata]] = {
    if (workspaceName == "exception") {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Exception getting workspace")))
    } else if (workspaceName == "invalid") {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "Workspace not found")))
    } else if (workspaceName == "largeSampleSet") {
      Future.successful(largeSampleSetMetadata)
    } else if (workspaceName == "large" || workspaceName == "page3exception") {
      Future.successful(largeSampleMetadata)
    } else {
      Future.successful(validEntitiesMetadata)
    }
  }

  override def getCatalog(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken) = {
    Future.successful(Seq(WorkspaceCatalog("user@gmail.com", true)))
  }

  override def patchCatalog(workspaceNamespace: String, workspaceName: String, updates: Seq[WorkspaceCatalog])(implicit userToken: WithAccessToken) = {
    val responses = updates.map(cat => WorkspaceCatalogResponse(cat.email.substring(0, cat.email.indexOf("@"))+"id", cat.catalog))
    Future.successful(WorkspaceCatalogUpdateResponseList(responses, Seq.empty))
  }


  override def getMethodConfigs(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken) =
    Future.successful(Seq.empty[MethodConfigurationShort])

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(true, None))

  def deleteWorkspace(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[WorkspaceDeleteResponse] = {
    Future.successful(WorkspaceDeleteResponse(Some("Your Google bucket 'bucketId' will be deleted within 24h.")))
  }

  override def createProject(projectName: String, billingAccount: String)(implicit userToken: WithAccessToken): Future[Boolean] = Future(false)

  override def getProjects(implicit userToken: WithAccessToken): Future[Seq[Trial.RawlsBillingProjectMembership]] = Future(Seq.empty[Trial.RawlsBillingProjectMembership])

  override def getProjectMembers(projectId: String)(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMember]] = ???
}
