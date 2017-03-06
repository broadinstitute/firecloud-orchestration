package org.broadinstitute.dsde.firecloud.dataaccess

import java.net.InetAddress

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ElasticSearch._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.elasticsearch.action.{ActionRequest, ActionRequestBuilder, ActionResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import spray.http.Uri.Authority
import spray.json._

import scala.util.{Failure, Success, Try}

trait ElasticSearchDAOSupport extends LazyLogging {

  def buildClient(servers:Seq[Authority]): TransportClient = {
    // cluster name is constant across environments; no need to add it to config
    val settings = Settings.settingsBuilder
      .put("cluster.name", "firecloud-elasticsearch")
      .build
    val addresses = servers map { server =>
      new InetSocketTransportAddress(InetAddress.getByName(server.host.address), server.port)
    }
    TransportClient.builder().settings(settings).build.addTransportAddresses(addresses: _*)
  }

  def executeESRequest[T <: ActionRequest[T], U <: ActionResponse, V <: ActionRequestBuilder[T, U, V]](req: V): U = {
    val tick = System.currentTimeMillis
    val responseTry = Try(req.get())
    val elapsed = System.currentTimeMillis - tick
    responseTry match {
      case Success(s) =>
        logger.debug(s"ElasticSearch %s request succeeded in %s ms.".format(req.getClass.getName, elapsed))
        s
      case Failure(f) =>
        logger.warn(s"ElasticSearch %s request failed in %s ms: %s".format(req.getClass.getName, elapsed, f.getMessage))
        throw new FireCloudException("ElasticSearch request failed", f)
    }
  }

  def makeMapping(attributeJson: String): String = {
    // generate mappings from the Library schema file
    val definition = attributeJson.parseJson.convertTo[AttributeDefinition]
    val attributeDetailMap = definition.properties filter(_._2.indexable.getOrElse(true)) map {
      case (label: String, detail: AttributeDetail) => createType(label, detail)
    }
    /* add the additional mappings that aren't tracked in the schema file:
     *   - _suggest property for autocomplete
     *   - _discoverableByGroups property to hold discover-mode permissions
     *   - parents.order and parents.label for ontology-aware search
     */
    val parentsInnerFields = Map(
      fieldOntologyParentsLabel -> ESInnerField("string"),
      fieldOntologyParentsOrder -> ESInnerField("integer", include_in_all = Some(false), index = Some("not_analyzed"))
    )

    val addlMappings:Map[String, ESPropertyFields] = Map(
      fieldSuggest -> ESType.suggestField("string"),
      fieldDiscoverableByGroups -> ESInternalType("string"),
      fieldOntologyParents -> ESObjectType(parentsInnerFields)
    )
    val props = attributeDetailMap ++ addlMappings
    ESDatasetProperty(props).toJson.prettyPrint
  }

  def createType(label: String, detail: AttributeDetail): (String, ESPropertyFields) = {
    val itemType = detail match {
      case x if x.`type` == "array" && x.items.isDefined => x.items.get.`type`
      case _ => detail.`type`
    }
    val searchSuggest = itemType == "string"
    val createSuggest = detail.typeahead.contains("populate")
    val isAggregate = detail match {
      case x if x.aggregate.isDefined => true
      case _ => false
    }
    label -> ESType(itemType, createSuggest, searchSuggest, isAggregate)
  }
}
