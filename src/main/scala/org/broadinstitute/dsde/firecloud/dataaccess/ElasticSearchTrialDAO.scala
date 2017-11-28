package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impTrialProject
import org.broadinstitute.dsde.firecloud.model.{SubsystemStatus, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.model.Trial.TrialProject
import org.broadinstitute.dsde.rawls.model.RawlsBillingProjectName
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.index.{IndexRequest, IndexRequestBuilder, IndexResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy
import org.elasticsearch.action.update.{UpdateRequest, UpdateRequestBuilder, UpdateResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.search.sort.SortOrder
import spray.json._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global


class ElasticSearchTrialDAO(client: TransportClient, indexName: String, refreshMode: RefreshPolicy = RefreshPolicy.NONE) extends TrialDAO with ElasticSearchDAOSupport {

  lazy private final val datatype = "billingproject"

  init // check for the presence of the index

  private def getProjectInternal(projectName: RawlsBillingProjectName): (Long, TrialProject) = {
    val verifyProjectQuery = termQuery("name.keyword", projectName.value)

    val verifyProjectRequest = client
      .prepareSearch(indexName)
      .setQuery(verifyProjectQuery)
      .addSort("name.keyword", SortOrder.ASC)
      .setSize(1)
      .setVersion(true)

    val verifyProjectResponse = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](verifyProjectRequest)

    if (verifyProjectResponse.getHits.totalHits == 0)
      throw new FireCloudException(s"project ${projectName.value} not found!")

    val hit = verifyProjectResponse.getHits.getAt(0)
    val version = hit.getVersion
    val project = verifyProjectResponse.getHits.getAt(0).getSourceAsString.parseJson.convertTo[TrialProject]

    (version, project)
  }

  override def getProject(projectName: RawlsBillingProjectName): TrialProject = {
    val (version, project) = getProjectInternal(projectName)
    project
  }

  override def createProject(projectName: RawlsBillingProjectName): TrialProject = {
    val trialProject = TrialProject(projectName)
    val insert = client
      .prepareIndex(indexName, datatype, projectName.value)
      .setSource(trialProject.toJson.compactPrint, XContentType.JSON)
      .setCreate(true) // fail the request if the project already exists
      .setRefreshPolicy(refreshMode)

    executeESRequest[IndexRequest, IndexResponse, IndexRequestBuilder](insert) // will throw error if insert fails
    trialProject
  }

  override def verifyProject(projectName: RawlsBillingProjectName, verified: Boolean): TrialProject = {
    val (version, project) = getProjectInternal(projectName)

    if (project.verified == verified) {
      project
    } else {
      val updatedProject = project.copy(verified = verified)
      val update = client
        .prepareUpdate(indexName, datatype, updatedProject.name.value)
        .setDoc(updatedProject.toJson.compactPrint, XContentType.JSON)
        .setVersion(version) // guarantee nobody else has updated in the meantime
        .setRefreshPolicy(refreshMode)

      executeESRequest[UpdateRequest, UpdateResponse, UpdateRequestBuilder](update) // will throw error if update fails
      updatedProject
    }
  }

  override def claimProject(userInfo: WorkbenchUserInfo): TrialProject = {
    val nextProjectQuery = boolQuery()
      .must(termQuery("verified", true))
      .mustNot(existsQuery("user.userSubjectId.keyword"))

    val nextProjectRequest = client
      .prepareSearch(indexName)
      .setQuery(nextProjectQuery)
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
    val update = client
      .prepareUpdate(indexName, datatype, updatedProject.name.value)
      .setDoc(updatedProject.toJson.compactPrint, XContentType.JSON)
      .setVersion(version) // guarantee nobody else has updated in the meantime
      .setRefreshPolicy(refreshMode)

    executeESRequest[UpdateRequest, UpdateResponse, UpdateRequestBuilder](update) // will throw error if update fails
    updatedProject
  }

  override def countAvailableProjects: Long = {
    val countProjectQuery = boolQuery()
      .must(termQuery("verified", true))
      .mustNot(existsQuery("user.userSubjectId.keyword"))

    val countProjectRequest = client
      .prepareSearch(indexName)
      .setQuery(countProjectQuery)
      .setSize(0)

    val countProjectResponse = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](countProjectRequest)

    countProjectResponse.getHits.totalHits
  }

  override def projectReport: Seq[TrialProject] = {
    val reportProjectQuery = boolQuery()
      .must(termQuery("verified", true))
      .must(existsQuery("user.userSubjectId.keyword"))

    val reportProjectRequest = client
      .prepareSearch(indexName)
      .setQuery(reportProjectQuery)
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
    if (!indexExists)
      throw new FireCloudException(s"index $indexName does not exist!")
    if (refreshMode != RefreshPolicy.NONE)
      logger.warn(s"refresh policy ${refreshMode.getValue} should only be used for testing")
  }

}
