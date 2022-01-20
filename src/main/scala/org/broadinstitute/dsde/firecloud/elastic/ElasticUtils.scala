package org.broadinstitute.dsde.firecloud.elastic

import java.net.InetAddress

import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.transport.client.PreBuiltTransportClient
import akka.http.scaladsl.model.Uri.Authority

object ElasticUtils {
  def buildClient(servers:Seq[Authority], clusterName: String): TransportClient = {
    val settings = Settings.builder
      .put("cluster.name", clusterName)
      .build
    val addresses = servers map { server =>
      new InetSocketTransportAddress(InetAddress.getByName(server.host.address), server.port)
    }
    new PreBuiltTransportClient(settings).addTransportAddresses(addresses: _*)
  }
}
