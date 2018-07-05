package org.broadinstitute.dsde.firecloud.dataaccess

import java.time.Instant

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impShare
import org.broadinstitute.dsde.firecloud.model.ShareLog.Share
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.elasticsearch.action.admin.indices.create.{CreateIndexRequest, CreateIndexRequestBuilder, CreateIndexResponse}
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.get.{GetRequest, GetRequestBuilder, GetResponse}
import org.elasticsearch.action.index.{IndexRequest, IndexRequestBuilder, IndexResponse}
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentType
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class ElasticSearchShareLogDAO(client: TransportClient, indexName: String, refreshMode: RefreshPolicy = RefreshPolicy.IMMEDIATE)
  extends ShareLogDAO with ElasticSearchDAOSupport {

  lazy private final val datatype = "sharelog"

  init // checks for the presence of the index

  override def logShare(userId: String, sharee: String, shareType: String): Share = {
    val share = Share(userId, sharee, shareType, Instant.now)
    val insert = client
      .prepareIndex(indexName, datatype, userId)
      .setSource(share.toJson.compactPrint, XContentType.JSON)
      .setCreate(true) // fail the request if the <thing> already exists
      .setRefreshPolicy(refreshMode)

    executeESRequest[IndexRequest, IndexResponse, IndexRequestBuilder](insert)
    share
  }

  override def getShares(userId: String): List[Share] = {
    val getSharesQuery = client.prepareGet(indexName, datatype, userId)
    Try(executeESRequest[GetRequest, GetResponse, GetRequestBuilder](getSharesQuery)) match {
      case Success(get) if get.isExists => get.getSourceAsString.parseJson.convertTo[List[Share]]
      case Success(_) => throw new FireCloudException(s"shares for $userId not found")
      case Failure(f) => throw new FireCloudException(s"error receiving shares for $userId: ${f.getMessage}")
    }
  }

  override def autocomplete(userId: String, term: String): List[String] = ???

  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(indexExists, None))

  private def indexExists: Boolean = {
    executeESRequest[IndicesExistsRequest, IndicesExistsResponse, IndicesExistsRequestBuilder](
    client.admin.indices.prepareExists(indexName)
    ).isExists
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

}
