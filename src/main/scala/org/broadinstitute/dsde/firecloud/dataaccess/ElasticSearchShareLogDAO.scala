package org.broadinstitute.dsde.firecloud.dataaccess

import java.time.Instant

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.ShareFormat
import org.broadinstitute.dsde.firecloud.model.ShareLog.Share
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.elasticsearch.action.admin.indices.create.{CreateIndexRequest, CreateIndexRequestBuilder, CreateIndexResponse}
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.get.{GetRequest, GetRequestBuilder, GetResponse}
import org.elasticsearch.action.index.{IndexRequest, IndexRequestBuilder, IndexResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.index.query.QueryBuilders._
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.hashing.MurmurHash3
import scala.util.{Failure, Success, Try}

trait ShareQueries {
  def userShares(userId: String): QueryBuilder = termQuery("userId", userId)
  def userSharesOfType(userId: String, shareType: Option[String]) = {
    if (shareType.isDefined)
      boolQuery().filter(
        boolQuery()
          .must(userShares(userId))
          .must(termQuery("shareType", shareType.get)))
    else
      userShares(userId)
  }

}

/**
  * DAO that uses ElasticSearch to log and get records of shares.
  *
  * @param client      The ElasticSearch client
  * @param indexName   The name of the target share log index in ElasticSearch
  */
class ElasticSearchShareLogDAO(client: TransportClient, indexName: String)
  extends ShareLogDAO with ElasticSearchDAOSupport with ShareQueries {

  lazy private final val datatype = "sharelog"

  init // checks for the presence of the index

  /**
    * Logs a record of a user sharing a workspace, group, or method with a user.
    *
    * @param userId     The workbench user id
    * @param sharee     The email of the user being shared with
    * @param shareType  The type (workspace, group, or method) see `ShareLog`
    * @return           The record of the share
    */
  override def logShare(userId: String, sharee: String, shareType: String): Share = {
    val share = Share(userId, sharee, shareType, Some(Instant.now))
    val id = MurmurHash3.stringHash(userId + sharee + shareType).toString
    val insert = client
      .prepareIndex(indexName, datatype, id)
      .setSource(share.toJson.compactPrint, XContentType.JSON)

    executeESRequest[IndexRequest, IndexResponse, IndexRequestBuilder](insert)
    share
  }

  /**
    * Gets a share by the ID, a `MurmurHash3` of `userId` + `sharee` + `shareType`
    *
    * @param share The share to get
    * @return A record of the share
    */
  override def getShare(share: Share): Share = {
    val id = generateId(share)
    val getSharesQuery = client.prepareGet(indexName, datatype, id)
    Try(executeESRequest[GetRequest, GetResponse, GetRequestBuilder](getSharesQuery)) match {
      case Success(get) if get.isExists => get.getSourceAsString.parseJson.convertTo[Share]
      case Success(_) => throw new FireCloudException(s"share not found")
      case Failure(f) => throw new FireCloudException(s"error getting share for $share: ${f.getMessage}")
    }
  }

  /**
    * Gets all shares that have been logged for a workbench user which fall under the
    * given type of share (workspace, method, group).
    *
    * @param userId     The workbench user ID
    * @param shareType  The type (workspace, group, or method) - if left blank returns all shares
    * @return A list of `ShareLog.Share`s
    */
  override def getShares(userId: String, shareType: Option[String] = None): Seq[Share] = {
    val getSharesRequest = client
      .prepareSearch(indexName)
      .setQuery(userSharesOfType(userId, shareType))
      .setSize(1000)

    val getSharesResponse = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](getSharesRequest)

    if (getSharesResponse.getHits.totalHits == 0)
      Seq.empty[Share]
    else
      getSharesResponse.getHits.getHits.toList map (_.getSourceAsString.parseJson.convertTo[Share])
  }

//  todo
//  override def autocomplete(userId: String, term: String): List[String] = ???

  private def indexExists: Boolean = {
    executeESRequest[IndicesExistsRequest, IndicesExistsResponse, IndicesExistsRequestBuilder](
    client.admin.indices.prepareExists(indexName)
    ).isExists
  }

  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(indexExists, None))

  private def init: Unit = {
    if (!indexExists) {
      executeESRequest[CreateIndexRequest, CreateIndexResponse, CreateIndexRequestBuilder](
      client.admin.indices.prepareCreate(indexName))
      // Try one more time and fail if index creation fails
      if (!indexExists)
      throw new FireCloudException(s"index $indexName does not exist!")
    }
  }

  def generateId(share: Share): String = {
    val rawId = Seq(share.userId, share.sharee, share.shareType).mkString
    MurmurHash3.stringHash(rawId).toString
  }
}
