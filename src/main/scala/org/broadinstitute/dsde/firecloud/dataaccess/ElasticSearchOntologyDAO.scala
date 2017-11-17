package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.SubsystemStatus
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.index.query.QueryBuilders._
import spray.http.Uri.Authority
import spray.json._

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOntologyTermResource

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ElasticSearchOntologyDAO(servers: Seq[Authority], clusterName: String, indexName: String) extends OntologyDAO with ElasticSearchDAOSupport {

  private val client: TransportClient = buildClient(servers, clusterName)

  private final val datatype = "ontology_term"


  override def search(term: String): Future[Option[List[TermResource]]] = Future(None)

  override def autocomplete(term: String): List[TermResource] = {
    val prefix = term.toLowerCase
    // user's term must be a prefix in either label or synonyms
    val query = boolQuery()
      .should(prefixQuery("label", prefix))
      .should(prefixQuery("synonyms", prefix))

    val searchRequest = client.prepareSearch(indexName)
      .setQuery(query)
        .setFetchSource(List("id","ontology","usable","label","synonyms","definition").toArray, null)

    val autocompleteResults = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](searchRequest)

    val allHits = autocompleteResults.getHits.getHits
    allHits.map { hit =>
      hit.getSourceAsString.parseJson.convertTo[TermResource]
    }.toList
  }

  private def indexExists: Boolean = {
    executeESRequest[IndicesExistsRequest, IndicesExistsResponse, IndicesExistsRequestBuilder](
      client.admin.indices.prepareExists(indexName)
    ).isExists
  }

  override def status: Future[SubsystemStatus] = {
    Future(SubsystemStatus(indexExists, None))
  }
}
