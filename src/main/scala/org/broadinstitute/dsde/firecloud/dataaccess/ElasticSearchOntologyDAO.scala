package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOntologyTermResource
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.client.{RequestOptions, RestHighLevelClient}
import org.elasticsearch.client.indices.GetIndexRequest
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.search.builder.SearchSourceBuilder
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ElasticSearchOntologyDAO(client: RestHighLevelClient, indexName: String) extends OntologyDAO with ElasticSearchDAOSupport {

  private final val datatype = "ontology_term"
  lazy private final val OPTS = RequestOptions.DEFAULT


  override def search(term: String): List[TermResource] = {
    val getRequest = new GetRequest(indexName, term)
    val getResult = elasticSearchRequest() {
      client.get(getRequest, OPTS)
    }
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

    val searchSourceBuilder = new SearchSourceBuilder()
    searchSourceBuilder.query(query)
    searchSourceBuilder.size(20)
    searchSourceBuilder.fetchSource(List("id","ontology","usable","label","synonyms","definition").toArray, null)

    val searchRequest = new SearchRequest(indexName)
    searchRequest.source(searchSourceBuilder)

    val autocompleteResults = elasticSearchRequest() {
      client.search(searchRequest, OPTS)
    }

    val allHits = autocompleteResults.getHits.getHits
    val termResources = allHits.map(_.getSourceAsString.parseJson.convertTo[TermResource]).toList

    logger.info(s"autocomplete for input [$term] resulted in: ${termResources.map(_.label)}")

    termResources
  }

  private def indexExists(): Boolean = {
    val getIndexRequest = new GetIndexRequest(indexName)
    elasticSearchRequest() {
      client.indices().exists(getIndexRequest, OPTS)
    }
  }

  override def status: Future[SubsystemStatus] = {
    Future(SubsystemStatus(indexExists(), None))
  }
}
