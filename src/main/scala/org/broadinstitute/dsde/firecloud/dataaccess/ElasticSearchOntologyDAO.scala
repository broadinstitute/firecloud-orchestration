package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOntologyTermResource
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.get.{GetRequest, GetRequestBuilder, GetResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.index.query.QueryBuilders._
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ElasticSearchOntologyDAO(client: TransportClient, indexName: String) extends OntologyDAO with ElasticSearchDAOSupport {

  private final val datatype = "ontology_term"


  override def search(term: String): List[TermResource] = {
    val getRequest = client.prepareGet(indexName, datatype, term)
    val getResult = executeESRequest[GetRequest, GetResponse, GetRequestBuilder](getRequest)
    if (!getResult.isExists) {
      List.empty[TermResource]
    } else {
      List(getResult.getSourceAsString.parseJson.convertTo[TermResource])
    }
  }

  override def autocomplete(term: String): List[TermResource] = {
    val prefix = term.toLowerCase
    // user's term must be a prefix in either label or synonyms
    val query = boolQuery()
        .must(termQuery("ontology.keyword", "Disease"))
        .must(termQuery("usable", true))
        .must(boolQuery()
          .should(termQuery("label.keyword", prefix).boost(10)) // exact match on label gets pushed to top
          .should(matchPhrasePrefixQuery("label", prefix).boost(5)) // prefix matches on label are more relevant than ...
          .should(matchPhrasePrefixQuery("synonyms", prefix)) /// prefix matches on synonyms
          .minimumShouldMatch(1) // match at least one of the above cases
        )

    val searchRequest = client.prepareSearch(indexName)
      .setQuery(query)
        .setSize(20)
        .setFetchSource(List("id","ontology","usable","label","synonyms","definition").toArray, null)

    val autocompleteResults = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](searchRequest)

    val allHits = autocompleteResults.getHits.getHits
    val termResources = allHits.map(_.getSourceAsString.parseJson.convertTo[TermResource]).toList

    logger.info(s"autocomplete for input [$term] resulted in: ${termResources.map(_.label)}")

    termResources
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
