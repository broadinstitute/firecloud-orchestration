package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.Ontology.{TermParent, TermResource}
import org.broadinstitute.dsde.firecloud.model.SubsystemStatus
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.index.query.QueryBuilders._
import spray.http.Uri.Authority
import spray.json._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOntologyTermResource
import org.elasticsearch.action.get.{GetRequest, GetRequestBuilder, GetResponse}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ElasticSearchOntologyDAO(servers: Seq[Authority], clusterName: String, indexName: String) extends OntologyDAO with ElasticSearchDAOSupport {

  private val client: TransportClient = buildClient(servers, clusterName)

  private final val datatype = "ontology_term"


  override def search(term: String): Future[Option[List[TermResource]]] = {
    val getRequest = client.prepareGet(indexName, datatype, term)
    val getResult = executeESRequest[GetRequest, GetResponse, GetRequestBuilder](getRequest)
    if (!getResult.isExists) {
      Future(None)
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
      Future(Some(List(annotatedTerm)))
    }
  }

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
