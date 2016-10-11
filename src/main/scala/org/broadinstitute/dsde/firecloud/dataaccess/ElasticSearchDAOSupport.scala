package org.broadinstitute.dsde.firecloud.dataaccess

import java.net.InetAddress

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.elasticsearch.action.{ActionRequest, ActionRequestBuilder, ActionResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import spray.http.Uri.Authority

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
    val responseTry = executeESRequestTry[T, U, V](req)
    responseTry match {
      case Success(s) =>
        logger.debug(s"ElasticSearch %s request succeeded.".format(req.getClass.getName))
        s
      case Failure(f) =>
        logger.warn("ElasticSearch request failed: " + f.getMessage)
        throw new FireCloudException("ElasticSearch request failed", f)
    }
  }
  def executeESRequestOption[T <: ActionRequest[T], U <: ActionResponse, V <: ActionRequestBuilder[T, U, V]](req: V): Option[U] = {
    val responseTry = executeESRequestTry[T, U, V](req)
    responseTry match {
      case Success(s) =>
        logger.debug(s"ElasticSearch %s request succeeded.".format(req.getClass.getName))
        Some(s)
      case Failure(f) =>
        logger.debug("ElasticSearch request failed: " + f.getMessage)
        None
    }
  }

  private def executeESRequestTry[T <: ActionRequest[T], U <: ActionResponse, V <: ActionRequestBuilder[T, U, V]](req: V): Try[U] = {
    Try(req.get())
  }

}
