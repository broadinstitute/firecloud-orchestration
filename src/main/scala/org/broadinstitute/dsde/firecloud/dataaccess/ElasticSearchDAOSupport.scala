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
    val definition = attributeJson.parseJson.convertTo[AttributeDefinition]
    val attributeDetailMap = definition.properties filter(_._2.indexable.getOrElse(true)) map {
      case (label: String, detail: AttributeDetail) => detailFromAttribute(label, detail)
    }
    // add the magic "_suggest" property that we'll use for autocomplete
    val props = attributeDetailMap +
      (fieldSuggest -> ESType.suggestField("string"),
        fieldDiscoverableByGroups -> new ESInternalType("string"))
    ESDatasetProperty(props).toJson.prettyPrint
  }

  def detailFromAttribute(label: String, detail: AttributeDetail): (String, ESPropertyFields) = {
    val itemType = detail match {
      case x if x.`type` == "array" && x.items.isDefined => x.items.get.`type`
      case _ => detail.`type`
    }
    detail match {
      case x if x.aggregate.isDefined => label -> ESAggregatableType(itemType)
      case _ => label -> ESType(itemType)
    }
  }

}
