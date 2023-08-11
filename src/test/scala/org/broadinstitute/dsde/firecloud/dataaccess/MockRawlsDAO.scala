package org.broadinstitute.dsde.firecloud.dataaccess

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.broadinstitute.dsde.firecloud.dataaccess.MockRawlsDAO._
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.AgoraConfigurationShort
import org.broadinstitute.dsde.firecloud.model.Project.ProjectRoles.ProjectRole
import org.broadinstitute.dsde.firecloud.model.Project.RawlsBillingProjectMember
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.LibraryService
import org.broadinstitute.dsde.firecloud.webservice.WorkspaceApiServiceSpec
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.joda.time.DateTime

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


  val validBigQueryEntities = List(
    Entity("shakespeare", "bigQuery", Map(AttributeName.withDefaultNS("query_str") -> AttributeString("SELECT * FROM [bigquery-public-data:samples.shakespeare] LIMIT 1000"))),
    Entity("king", "bigQuery", Map(AttributeName.withDefaultNS("query_str") -> AttributeString("SELECT * FROM [bigquery-public-data:samples.king] LIMIT 1000")))
  )

  val validBigQuerySetEntities = List(
    Entity("settest", "bigQuery_set", Map(AttributeName.withDefaultNS("bigQuerys") -> AttributeEntityReferenceList(Seq(
      AttributeEntityReference("bigQuery", "shakespeare"),
      AttributeEntityReference("bigQuery", "king")))))
  )

  val nonModelPairEntities = List(
    Entity("RomeoAndJuliet", "pair", Map(AttributeName.withDefaultNS("names") -> AttributeValueList(Seq(AttributeString("Romeo"), AttributeString("Juliet"))))),
    Entity("PB&J", "pair", Map(AttributeName.withDefaultNS("names") -> AttributeValueList(Seq(AttributeString("PeanutButter"), AttributeString("Jelly")))))
  )

  val namespacedEntities = List(
    Entity("first", "study", Map(
      AttributeName.withDefaultNS("foo") -> AttributeString("default-foovalue"),
      AttributeName.fromDelimitedName("tag:study_id") -> AttributeString("first-id"),
      AttributeName.fromDelimitedName("tag:foo") -> AttributeString("namespaced-foovalue")
    )),
    Entity("second", "study", Map(
      AttributeName.withDefaultNS("foo") -> AttributeString("default-bar"),
      AttributeName.fromDelimitedName("tag:study_id") -> AttributeString("second-id"),
      AttributeName.fromDelimitedName("tag:foo") -> AttributeString("namespaced-bar")
    ))
  )

  val nonModelBigQueryMetadata = Map(
    "bigQuery" -> EntityTypeMetadata(
      count = 2,
      idName = "bigQuery_id",
      attributeNames = Seq("query_str")))

  val nonModelBigQuerySetMetadata = Map(
    "bigQuery_set" -> EntityTypeMetadata(
      count = 1,
      idName = "bigQuery_set_id",
      attributeNames = Seq("bigQuerys")))

  val nonModelPairMetadata = Map(
    "pair" -> EntityTypeMetadata(
      count = 2,
      idName = "pair_id",
      attributeNames = Seq("names")))

  val namespacedMetadata = Map(
    "study" -> EntityTypeMetadata(
      count = 2,
      idName = "study_id",
      attributeNames = Seq("foo", "tag:foo", "tag:study_id")))

}


/**
  * Created by davidan on 9/28/16.
  *
  */
class MockRawlsDAO extends RawlsDAO {

  private val rawlsWorkspaceWithAttributes = WorkspaceDetails(
    "attributes",
    "att",
    "id",
    "", //bucketname
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "ansingh",
    Some(Map(AttributeName("default", "a") -> AttributeBoolean(true),
      AttributeName("default", "b") -> AttributeNumber(1.23),
      AttributeName("default", "c") -> AttributeString(""),
      AttributeName("default", "d") -> AttributeString("escape quo\"te"),
      AttributeName("default", "e") -> AttributeString("this\thas\ttabs\tin\tit"),
      AttributeName("default", "f") -> AttributeValueList(Seq(
        AttributeString("v6"),
        AttributeNumber(999),
        AttributeBoolean(true)
      )))),
    false,
    Some(Set.empty), //authdomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    None,
    Option(DateTime.now()),
    None,
    None,
    WorkspaceState.Ready
  )

  val publishedRawlsWorkspaceWithAttributes = WorkspaceDetails(
    "attributes",
    "att",
    "id",
    "", //bucketname
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "ansingh",
    Some(Map(AttributeName("default", "a") -> AttributeBoolean(true),
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
      )))),
    false,
    Some(Set.empty), //authdomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    None,
    Option(DateTime.now()),
    None,
    None,
    WorkspaceState.Ready
  )

  val unpublishedRawlsWorkspaceLibraryValid = WorkspaceDetails(
    "attributes",
    "att",
    "id",
    "", //bucketname
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "ansingh",
    Some(Map(
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
    )),
    false,
    Some(Set.empty), //authdomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    None,
    Option(DateTime.now()),
    None,
    None,
    WorkspaceState.Ready
  )

  val rawlsWorkspaceResponseWithAttributes = WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare = Some(false), canCompute = Some(true), catalog = Some(false), rawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None)
  val publishedRawlsWorkspaceResponseWithAttributes = WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare = Some(false), canCompute = Some(true), catalog = Some(false), publishedRawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None)

  def newWorkspace: WorkspaceDetails = {
    WorkspaceDetails(
      namespace = "namespace",
      name = "name",
      workspaceId = "workspaceId",
      bucketName = "bucketName",
      workflowCollectionName = Some("wf-collection"),
      createdDate = DateTime.now(),
      lastModified = DateTime.now(),
      createdBy = "createdBy",
      attributes = Option(Map.empty),
      isLocked = true,
      authorizationDomain = Some(Set.empty),
      workspaceVersion = WorkspaceVersions.V2,
      googleProject = GoogleProjectId("googleProject"),
      googleProjectNumber = Some(GoogleProjectNumber("googleProjectNumber")),
      billingAccount = Some(RawlsBillingAccountName("billingAccount")),
      completedCloneWorkspaceFileTransfer = Option(DateTime.now()),
      workspaceType = None,
      cloudPlatform = None,
      state = WorkspaceState.Ready
    )
  }

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = Future.successful(false)

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = {
    Future.successful(userInfo.id == "curator")
  }

  override def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse] = {
    Future.successful(BucketUsageResponse(BigInt("256000000000"), Option(new DateTime(0))))
  }

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] = {
    ns match {
      case "projectowner" => Future(WorkspaceResponse(Some(WorkspaceAccessLevels.ProjectOwner), canShare = Some(true), canCompute = Some(true), catalog = Some(false), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "reader" => Future(WorkspaceResponse(Some(WorkspaceAccessLevels.Read), canShare = Some(false), canCompute = Some(true), catalog = Some(false), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "attributes" => Future(rawlsWorkspaceResponseWithAttributes)
      case "publishedreader" => Future(WorkspaceResponse(Some(WorkspaceAccessLevels.Read), canShare = Some(false), canCompute = Some(true), catalog = Some(false), publishedRawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "publishedreadercatalog" => Future(WorkspaceResponse(Some(WorkspaceAccessLevels.Read), canShare = Some(false), canCompute = Some(true), catalog = Some(true), publishedRawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "publishedwriter" => Future(WorkspaceResponse(Some(WorkspaceAccessLevels.Write), canShare = Some(false), canCompute = Some(true), catalog = Some(false), publishedRawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "unpublishedwriter" => Future(WorkspaceResponse(Some(WorkspaceAccessLevels.Write), canShare = Some(false), canCompute = Some(true), catalog = Some(false), rawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "publishedowner" => Future.successful(WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare = Some(true), canCompute = Some(true), catalog = Some(false), publishedRawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "libraryValid" => Future.successful(WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare = Some(true), canCompute = Some(true), catalog = Some(false), unpublishedRawlsWorkspaceLibraryValid, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "usBucketWorkspace" => Future.successful(WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare = Some(true), canCompute = Some(true), catalog = Some(false), newWorkspace.copy(bucketName = "usBucket"), Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case "europeWest1BucketWorkspace" => Future.successful(WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare = Some(true), canCompute = Some(true), catalog = Some(false), newWorkspace.copy(bucketName = "europeWest1Bucket"), Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
      case _ => Future.successful(WorkspaceResponse(Some(WorkspaceAccessLevels.Owner), canShare = Some(true), canCompute = Some(true), catalog = Some(false), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None))
    }
  }

  override def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]] = {
    Future.successful(Seq(WorkspaceListResponse(WorkspaceAccessLevels.ProjectOwner, newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false),
      WorkspaceListResponse(WorkspaceAccessLevels.Read, newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, rawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, publishedRawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false)))
  }

  override def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails] = {
    if (name == WorkspaceApiServiceSpec.publishedWorkspace.name) {
      Future.successful(publishedRawlsWorkspaceWithAttributes)
    } else {
      Future.successful(newWorkspace)
    }
  }

  override def updateLibraryAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails] = {
    Future.successful((newWorkspace))
  }

  override def getAllLibraryPublishedWorkspaces(implicit userToken: WithAccessToken): Future[Seq[WorkspaceDetails]] = Future.successful(Seq.empty[WorkspaceDetails])

  override def getWorkspaceACL(ns: String, name: String)(implicit userToken: WithAccessToken) =
    Future.successful(WorkspaceACL(Map.empty[String, AccessEntry]))

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate], inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList] = {
    Future.successful(WorkspaceACLUpdateResponseList(aclUpdates.toSet, aclUpdates.toSet, aclUpdates.toSet))
  }

  override def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[Entity]] = {
    if (workspaceName == "invalid") {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "Workspace not found")))
    } else {
      entityType match {
        case "sample" =>
          val sampleAtts = Map(
            AttributeName.withDefaultNS("sample_type") -> AttributeString("Blood"),
            AttributeName.withDefaultNS("ref_fasta") -> AttributeString("gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.fasta"),
            AttributeName.withDefaultNS("ref_dict") -> AttributeString("gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.dict"),
            AttributeName.withDefaultNS("participant_id") -> AttributeEntityReference("participant", "subject_HCC1143")
          )
          Future.successful(List(Entity("sample_01", "sample", sampleAtts)))
        case "participant" =>
          val participantAtts = Map(
            AttributeName.withDefaultNS("tumor_platform") -> AttributeString("illumina"),
            AttributeName.withDefaultNS("ref_fasta") -> AttributeString("gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.fasta"),
            AttributeName.withDefaultNS("tumor_strip_unpaired") -> AttributeString("TRUE")
          )
          Future.successful(List(Entity("subject_HCC1143", "participant", participantAtts)))
        case _ =>
          Future.successful(Seq.empty)
      }
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
    } else if (workspaceName == "nonModel") {
      val queryResponse: EntityQueryResponse = EntityQueryResponse(
        parameters = query,
        resultMetadata = EntityQueryResultMetadata(unfilteredCount = 2, filteredCount = 2, filteredPageCount = 1),
        results = validBigQueryEntities
      )
      Future.successful(queryResponse)
    } else if (workspaceName == "nonModelSet") {
      val queryResponse: EntityQueryResponse = EntityQueryResponse(
        parameters = query,
        resultMetadata = EntityQueryResultMetadata(unfilteredCount = 1, filteredCount = 1, filteredPageCount = 1),
        results = validBigQuerySetEntities
      )
      Future.successful(queryResponse)
    } else if (workspaceName == "nonModelPair") {
      val queryResponse: EntityQueryResponse = EntityQueryResponse(
        parameters = query,
        resultMetadata = EntityQueryResultMetadata(unfilteredCount = 2, filteredCount = 2, filteredPageCount = 1),
        results = nonModelPairEntities
      )
      Future.successful(queryResponse)
    } else if (workspaceName == "namespacedEntities") {
      val queryResponse: EntityQueryResponse = EntityQueryResponse(
        parameters = query,
        resultMetadata = EntityQueryResultMetadata(unfilteredCount = 2, filteredCount = 2, filteredPageCount = 1),
        results = namespacedEntities
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
    } else if (workspaceName == "nonModel") {
      Future.successful(nonModelBigQueryMetadata)
    } else if (workspaceName == "nonModelSet") {
      Future.successful(nonModelBigQuerySetMetadata)
    } else if (workspaceName == "nonModelPair") {
      Future.successful(nonModelPairMetadata)
    } else if (workspaceName == "namespacedEntities") {
      Future.successful(namespacedMetadata)
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


  override def getAgoraMethodConfigs(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken) =
    Future.successful(Seq.empty[AgoraConfigurationShort])

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(true, None))

  def deleteWorkspace(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[Option[String]] = {
    Future.successful(Some("Your Google bucket 'bucketId' will be deleted within 24h."))
  }

  override def getProjects(implicit userToken: WithAccessToken): Future[Seq[Project.RawlsBillingProjectMembership]] = Future(Seq.empty[Project.RawlsBillingProjectMembership])

  override def getProjectMembers(projectId: String)(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMember]] =
    Future(Seq.empty)

  override def addUserToBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean] = Future(true)

  override def removeUserFromBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean] = Future(true)

  override def batchUpsertEntities(workspaceNamespace: String, workspaceName: String, entityType: String, upserts: Seq[EntityUpdateDefinition])(implicit userToken: UserInfo): Future[HttpResponse] = Future.successful(HttpResponse(StatusCodes.NoContent))

  override def batchUpdateEntities(workspaceNamespace: String, workspaceName: String, entityType: String, updates: Seq[EntityUpdateDefinition])(implicit userToken: UserInfo): Future[HttpResponse] = Future.successful(HttpResponse(StatusCodes.NoContent))

  override def cloneWorkspace(workspaceNamespace: String, workspaceName: String, cloneRequest: WorkspaceRequest)(implicit userToken: WithAccessToken): Future[WorkspaceDetails] = Future.successful(WorkspaceDetails(cloneRequest.namespace, cloneRequest.name, "id", "bucket", Some("workflow-collection-id"), DateTime.now(), DateTime.now(), "test-user", Some(cloneRequest.attributes), false, cloneRequest.authorizationDomain, WorkspaceVersions.V2, GoogleProjectId("googleProject"), Some(GoogleProjectNumber("googleProjectNumber")), Some(RawlsBillingAccountName("billingAccount")), None, None, Option(DateTime.now()), None, None, WorkspaceState.Ready))
}
