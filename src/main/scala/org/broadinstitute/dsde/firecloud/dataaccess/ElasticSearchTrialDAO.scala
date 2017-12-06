package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impTrialProject
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses.CreationStatus
import org.broadinstitute.dsde.firecloud.model.WorkbenchUserInfo
import org.broadinstitute.dsde.firecloud.model.Trial.{CreationStatuses, TrialProject}
import org.broadinstitute.dsde.rawls.model.RawlsBillingProjectName
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.elasticsearch.action.admin.indices.create.{CreateIndexRequest, CreateIndexRequestBuilder, CreateIndexResponse}
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.get.{GetRequest, GetRequestBuilder, GetResponse}
import org.elasticsearch.action.index.{IndexRequest, IndexRequestBuilder, IndexResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy
import org.elasticsearch.action.update.{UpdateRequest, UpdateRequestBuilder, UpdateResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.search.sort.SortOrder
import spray.json._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

trait TrialQueries {

  val Unverified:QueryBuilder = termQuery("verified", false)

  val Errored:QueryBuilder = boolQuery()
    .must(termQuery("verified", true))
    .must(termQuery("status.keyword", CreationStatuses.Error.toString))

  val Available:QueryBuilder = boolQuery()
    .must(termQuery("verified", true))
    .must(termQuery("status.keyword", CreationStatuses.Ready.toString))
    .mustNot(existsQuery("user.userSubjectId.keyword"))

  val Claimed:QueryBuilder = boolQuery()
    .must(termQuery("verified", true))
    .must(termQuery("status.keyword", CreationStatuses.Ready.toString))
    .must(existsQuery("user.userSubjectId.keyword"))
}

/**
  * DAO, talking to Elasticsearch, for the free trial project pool.
  *
  * @param client Elasticsearch client.
  * @param indexName name of the target free trial index.
  * @param refreshMode refresh policy. Using IMMEDIATE - the default - is a performance hit but ensures transactionality
  *                    of updates across multiple users.
  */
class ElasticSearchTrialDAO(client: TransportClient, indexName: String, refreshMode: RefreshPolicy = RefreshPolicy.IMMEDIATE)
  extends TrialDAO with TrialQueries with ElasticSearchDAOSupport {

  lazy private final val datatype = "billingproject"

  init // checks for the presence of the index

  // because the two following methods are not defined at the TrialDAO level, they will
  // be inaccessible to most callers. Callers should be in the habit of using:
  //    val dao:TrialDAO = ElasticSearchTrialDAO(...)
  // which will limit callers to those methods defined in TrialDAO.

  // gets a single project record, with its Elasticsearch version
  def getProjectInternal(projectName: RawlsBillingProjectName): (Long, TrialProject) = {
    val getProjectQuery = client.prepareGet(indexName, datatype, projectName.value)

    val getProjectResponse = Try(executeESRequest[GetRequest, GetResponse, GetRequestBuilder](getProjectQuery))

    getProjectResponse match {
      case Success(get) if get.isExists =>
        val project = get.getSourceAsString.parseJson.convertTo[TrialProject]
        val version = get.getVersion
        (version, project)
      case Success(notfound) => throw new FireCloudException(s"project ${projectName.value} not found!")
      case Failure(f) => throw new FireCloudException(s"error retrieving project [${projectName.value}]: ${f.getMessage}")
    }
  }

  // update a project, with a check on its Elasticsearch version
  def updateProjectInternal(updatedProject: TrialProject, version: Long) = {
    val update = client
      .prepareUpdate(indexName, datatype, updatedProject.name.value)
      .setDoc(updatedProject.toJson.compactPrint, XContentType.JSON)
      .setVersion(version) // guarantee nobody else has updated in the meantime
      .setRefreshPolicy(refreshMode)

    executeESRequest[UpdateRequest, UpdateResponse, UpdateRequestBuilder](update) // will throw error if update fails
  }

  /**
    * Read the record for a specified project. Throws an error if record not found.
    *
    * @param projectName name of the project record to read.
    * @return the project record
    */
  override def getProjectRecord(projectName: RawlsBillingProjectName): TrialProject = {
    val (_, project) = getProjectInternal(projectName)
    project
  }

  /**
    * Check to see if the project record exists.
    *
    * @param projectName name of the project record to read.
    * @return whether or not the project record exists
    */
  override def projectRecordExists(projectName: RawlsBillingProjectName): Boolean = {
    Try(getProjectInternal(projectName)).isSuccess
  }

  /**
    * Create a record for the specified project. Throws error if name
    * already exists or could not be otherwise created.
    *
    * @param projectName name of the project to use when creating a record
    * @return the created project record
    */
  override def insertProjectRecord(projectName: RawlsBillingProjectName): TrialProject = {
    val trialProject = TrialProject(projectName)
    val insert = client
      .prepareIndex(indexName, datatype, projectName.value)
      .setSource(trialProject.toJson.compactPrint, XContentType.JSON)
      .setCreate(true) // fail the request if the project already exists
      .setRefreshPolicy(refreshMode)

    executeESRequest[IndexRequest, IndexResponse, IndexRequestBuilder](insert) // will throw error if insert fails
    trialProject
  }

  /**
    * Update the "verified" field for a specified project record. The "verified" field indicates whether
    * or not the associated billing project was created successfully in Google Cloud. Throws an error if
    * the record was not found or the record could not be updated.
    *
    * @param projectName name of the project record to update
    * @param verified verified value with which to update the project record
    * @return the updated project record
    */
  override def setProjectRecordVerified(projectName: RawlsBillingProjectName, verified: Boolean, status: CreationStatus): TrialProject = {
    val (version, project) = getProjectInternal(projectName)

    if (project.verified == verified && project.status.contains(status)) {
      project // noop
    } else {
      val updatedProject = project.copy(verified = verified, status=Some(status))
      updateProjectInternal(updatedProject, version) // will throw error if update fails
      updatedProject
    }
  }

  /**
    * Associates the next-available project record with a specified user. "Next available"
    * is defined as verified, unclaimed, and first alphabetically by project name.
    * Throws an error if no project records are available, or if the project
    * record could not be updated.
    *
    * @param userInfo the user (email and subjectid) with which to update the project record.
    * @return the updated project record
    */
  override def claimProjectRecord(userInfo: WorkbenchUserInfo): TrialProject = {
    // if we find regular race conditions in which multiple users attempt to claim the "next" project,
    // we could change this to return N (= ~20) available projects, then choose a random project
    // from that list. Either way, the caller of this method should retry this method to ensure
    // a successful claim.
    val nextProjectRequest = client
      .prepareSearch(indexName)
      .setQuery(Available)
      .addSort("name.keyword", SortOrder.ASC)
      .setSize(1)
      .setVersion(true)

    val nextProjectResponse = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](nextProjectRequest)

    if (nextProjectResponse.getHits.totalHits == 0)
      throw new FireCloudException("no available projects")

    val hit = nextProjectResponse.getHits.getAt(0)
    val version = hit.getVersion
    val project = nextProjectResponse.getHits.getAt(0).getSourceAsString.parseJson.convertTo[TrialProject]
    assert(project.user.isEmpty)

    val updatedProject = project.copy(user = Some(userInfo))
    updateProjectInternal(updatedProject, version) // will throw error if update fails
    updatedProject
  }

  /**
    * Returns a list of project records in the pool that are unverified.
    *
    * @return list of project records in the pool that are unverified.
    */
  override def listUnverifiedProjects: Seq[TrialProject] = {
    val unverifiedRequest = client
      .prepareSearch(indexName)
      .setQuery(Unverified)
      .setSize(1000)

    val unverifiedResponse = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](unverifiedRequest)

    if (unverifiedResponse.getHits.totalHits == 0)
      Seq.empty[TrialProject]
    else
      unverifiedResponse.getHits.getHits.toSeq map ( _.getSourceAsString.parseJson.convertTo[TrialProject] )
  }

  /**
    * Returns a counts of project records by status.
    * @return counts of project records.
    */
  override def countProjects: Map[String,Long] = {
    Map(
      "unverified" -> count(Unverified),
      "errored" -> count(Errored),
      "available" -> count(Available),
      "claimed" -> count(Claimed)
    )
  }

  /**
    * Returns a list of project records that have associated users.
    * @return list of project records that have associated users.
    */
  override def projectReport: Seq[TrialProject] = {
    val reportProjectRequest = client
      .prepareSearch(indexName)
      .setQuery(Claimed)
      .addSort("name.keyword", SortOrder.ASC)
      .setSize(1000)

    val reportProjectResponse = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](reportProjectRequest)

    if (reportProjectResponse.getHits.totalHits == 0)
      Seq.empty[TrialProject]
    else
      reportProjectResponse.getHits.getHits.toSeq map ( _.getSourceAsString.parseJson.convertTo[TrialProject] )
  }

  /**
    * Returns a list of all project records that have no associated users.
    * @return list of project records that have no associated users.
    */
  override def availableProjectReport: Seq[TrialProject] = {
    val reportProjectRequest = client
      .prepareSearch(indexName)
      .setQuery(Available)
      .addSort("name.keyword", SortOrder.ASC)
      .setSize(1000)

    val reportProjectResponse = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](reportProjectRequest)

    if (reportProjectResponse.getHits.totalHits == 0)
      Seq.empty[TrialProject]
    else
      reportProjectResponse.getHits.getHits.toSeq map ( _.getSourceAsString.parseJson.convertTo[TrialProject] )
  }

  private def indexExists: Boolean = {
    executeESRequest[IndicesExistsRequest, IndicesExistsResponse, IndicesExistsRequestBuilder](
      client.admin.indices.prepareExists(indexName)
    ).isExists
  }

  override def status: Future[SubsystemStatus] = {
    Future(SubsystemStatus(indexExists, None))
  }

  private def init: Unit = {
    if (!indexExists) {
      executeESRequest[CreateIndexRequest, CreateIndexResponse, CreateIndexRequestBuilder](
        client.admin.indices.prepareCreate(indexName))
      // Try one more time and fail if index creation fails
      if (!indexExists)
        throw new FireCloudException(s"index $indexName does not exist!")
    }
  }

  private def count(qb: QueryBuilder): Long = {
    val countProjectRequest = client
      .prepareSearch(indexName)
      .setQuery(qb)
      .setSize(0)

    val countProjectResponse = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](countProjectRequest)

    countProjectResponse.getHits.totalHits
  }

}
