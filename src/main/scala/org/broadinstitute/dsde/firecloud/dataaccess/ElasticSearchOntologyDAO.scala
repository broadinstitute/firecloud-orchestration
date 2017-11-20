package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.SubsystemStatus
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.index.query.QueryBuilders._
import spray.json._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOntologyTermResource
import org.elasticsearch.action.get.{GetRequest, GetRequestBuilder, GetResponse}

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
      val term = getResult.getSourceAsString.parseJson.convertTo[TermResource]

      val annotatedTerm = if (term.parents.nonEmpty && term.parents.get.nonEmpty) {
        val parents = term.parents.get
        val ids = parents map (_.id)
        val query = client.prepareSearch(indexName).setQuery(idsQuery().addIds(ids:_*))
        val idsResult = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](query)
        val hitMap = idsResult.getHits.getHits.map { hit =>
          hit.getId -> hit.getSourceAsString.parseJson.convertTo[TermResource]
        }.toMap
        val annotatedParents = parents map { origParent =>
          val newLabel = hitMap.get(origParent.id) map (_.label)
          origParent.copy(label = newLabel)
        }
        term.copy(parents = Some(annotatedParents))
      } else {
        term
      }
      List(annotatedTerm)
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
    allHits.map(_.getSourceAsString.parseJson.convertTo[TermResource]).toList
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
