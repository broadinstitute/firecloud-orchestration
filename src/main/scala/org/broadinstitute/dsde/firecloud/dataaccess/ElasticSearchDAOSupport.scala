package org.broadinstitute.dsde.firecloud.dataaccess

import java.net.InetAddress

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model._
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
    val maps = definition.properties flatMap { case (label: String, detail: AttributeDetail) =>
      detailFromAttribute(label, detail)
    }
    ESDatasetProperty(maps).toJson.prettyPrint
  }

  def detailFromAttribute(label: String, detail: AttributeDetail) = {
    detail match {
      // weirdness for when we have an array type: https://www.elastic.co/guide/en/elasticsearch/reference/1.4/mapping-array-type.html
      case AttributeDetail(t, Some(items)) => assert(t == "array"); Map(label -> Right(ESArray(ESItem(ESDetail(items.`type`)))))
      case AttributeDetail(t, None) => Map(label -> Left(ESDetail(detail.`type`)))
    }
  }

}
