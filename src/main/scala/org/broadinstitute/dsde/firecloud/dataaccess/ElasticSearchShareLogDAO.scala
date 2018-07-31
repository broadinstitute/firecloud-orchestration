package org.broadinstitute.dsde.firecloud.dataaccess

import java.time.Instant

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impShareFormat
import org.broadinstitute.dsde.firecloud.model.ShareLog.{Share, ShareType}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.elasticsearch.action.admin.indices.create.{CreateIndexRequest, CreateIndexRequestBuilder, CreateIndexResponse}
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.get.{GetRequest, GetRequestBuilder, GetResponse}
import org.elasticsearch.action.index.{IndexRequest, IndexRequestBuilder, IndexResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy
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
  /**
    * Makes an ElasticSearch query builder to get user shares
    * @param userId     ID of user whose shares to get
    * @param shareType  Optional type of share, if left empty will get all types
    * @return           The search query
    */
  def userShares(userId: String, shareType: Option[ShareType.Value] = None): QueryBuilder = {
    // always include the sharer in query criteria
    val userIdQuery = boolQuery().must(termQuery("userId", userId))
    // if a shareType was specified, include that also
    shareType map { typeOfShare => userIdQuery.must(termQuery("shareType", typeOfShare.toString)) }
    userIdQuery
  }

}

/**
  * DAO that uses ElasticSearch to log and get records of shares.
  *
  * @param client      The ElasticSearch client
  * @param indexName   The name of the target share log index in ElasticSearch
  */
class ElasticSearchShareLogDAO(client: TransportClient, indexName: String, refreshMode: RefreshPolicy = RefreshPolicy.NONE)
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
  override def logShare(userId: String, sharee: String, shareType: ShareType.Value): Share = {
    val share = Share(userId, sharee, shareType, Some(Instant.now))
    val id = generateId(share)
    val insert = client
      .prepareIndex(indexName, datatype, id)
      .setSource(share.toJson.compactPrint, XContentType.JSON)
      .setRefreshPolicy(refreshMode)

    executeESRequest[IndexRequest, IndexResponse, IndexRequestBuilder](insert)
    share
  }

  /**
    * Logs records of a user sharing a workspace, group, or method with users.
    * todo could be a batch query
    *
    * @param userId The workbench user id
    * @param sharees The emails of the users being shared with
    * @param shareType The type (workspace, group, or method) see `ShareLog`
    * @return The records of the shares - see `ShareLog.Share`
    */
  override def logShares(userId: String, sharees: Seq[String], shareType: ShareType.Value): Seq[Share] = {
    sharees map { sharee => logShare(userId, sharee, shareType) }
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
    * Gets up to 100 shares that have been logged for a workbench user which fall under the
    * given type of share (workspace, method, group).
    * Todo when 100 shares have been reached it's probably time to implement autocomplete.
    *
    * @param userId     The workbench user ID
    * @param shareType  The type (workspace, group, or method) - if left blank returns all shares
    * @return A list of `ShareLog.Share`s
    */
  override def getShares(userId: String, shareType: Option[ShareType.Value] = None): Seq[Share] = {
    val getSharesRequest = client
      .prepareSearch(indexName)
      .setQuery(userShares(userId, shareType))
      .setSize(100)

    val getSharesResponse = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](getSharesRequest)
    getSharesResponse.getHits match {
      case hits =>
        if (hits.totalHits == 0)
          Seq.empty[Share]
        else
          if (hits.totalHits >= 100) logger.warn(s"Number of shares for user $userId has reached or exceeded 100.")
          getSharesResponse.getHits.getHits.toList map (_.getSourceAsString.parseJson.convertTo[Share])
    }
  }

//  todo Leveraging ElasticSearch's autocomplete functionality will likely be useful
//       as we iterate and the number of shares increases to such a size that the list of
//       shares returned by `getShares` is too large for HTML autocomplete to efficiently handle.
//       Rather than limit the number of values returned, it ought to be beneficial to let
//       ElasticSearch handle the autocompletion.
//
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

  /**
    * Uses MurmurHash3 for quick hashing -
    * @see [[https://github.com/aappleby/smhasher]]
    *
    * @param share the share to create the hash from
    * @return the hash
    */
  def generateId(share: Share): String = {
    val rawId = Seq(share.userId, share.sharee, share.shareType).mkString("|")
    MurmurHash3.stringHash(rawId).toString
  }
}
