package org.broadinstitute.dsde.firecloud.dataaccess

import org.elasticsearch.action.admin.indices.create.{CreateIndexRequest, CreateIndexRequestBuilder, CreateIndexResponse}
import org.elasticsearch.action.admin.indices.delete.{DeleteIndexRequest, DeleteIndexRequestBuilder, DeleteIndexResponse}
import org.elasticsearch.action.bulk.{BulkRequest, BulkRequestBuilder, BulkResponse}
import org.elasticsearch.client.transport.TransportClient
import spray.http.Uri.Authority
import spray.json.JsObject

/**
  * Created by davidan on 9/28/16.
  */
class ElasticSearchDAO(servers:Seq[Authority], indexName: String) extends SearchDAO with ElasticSearchDAOSupport {

  private val client: TransportClient = buildClient(servers)
  private final val datatype = "dataset"


  override def deleteIndex = {
    // it's possible the index doesn't exist, so we need to beware of errors
    executeESRequestOption[DeleteIndexRequest, DeleteIndexResponse, DeleteIndexRequestBuilder]( client.admin().indices().prepareDelete(indexName) )
    executeESRequest[CreateIndexRequest, CreateIndexResponse, CreateIndexRequestBuilder]( client.admin().indices().prepareCreate(indexName) )
  }

  override def bulkIndex(docs: Seq[(String, JsObject)]) = {
    val bulkRequest = client.prepareBulk()
    docs map {
      case (id: String, doc: JsObject) => bulkRequest.add(client.prepareIndex(indexName, datatype, id).setSource(doc.compactPrint))
    }

    val bulkResponse = executeESRequest[BulkRequest, BulkResponse, BulkRequestBuilder](bulkRequest)
    if (bulkResponse.hasFailures)
      logger.warn(bulkResponse.buildFailureMessage())
  }

}
