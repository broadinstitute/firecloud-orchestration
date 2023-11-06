package org.broadinstitute.dsde.firecloud.elastic

import java.net.InetAddress
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.transport.client.PreBuiltTransportClient
import akka.http.scaladsl.model.Uri.Authority
import org.elasticsearch.index.reindex.ReindexPlugin
import org.elasticsearch.join.ParentJoinPlugin
import org.elasticsearch.percolator.PercolatorPlugin
import org.elasticsearch.plugins.Plugin
import org.elasticsearch.script.mustache.MustachePlugin
import org.elasticsearch.transport.Netty4Plugin

import java.util
import java.util.Collections

object ElasticUtils {

  // copied from PreBuiltTransportClient, but removed Netty3Plugin
  val pluginList: util.Collection[Class[_ <: Plugin]] = Collections.unmodifiableList(util.Arrays.asList(
    // classOf[Netty3Plugin],
    classOf[Netty4Plugin],
    classOf[ReindexPlugin],
    classOf[PercolatorPlugin],
    classOf[MustachePlugin],
    classOf[ParentJoinPlugin]))

  def buildClient(servers: Seq[Authority], clusterName: String): TransportClient = {
    val settings = Settings.builder
      .put("cluster.name", clusterName)
      .build
    val addresses = servers map { server =>
      new InetSocketTransportAddress(InetAddress.getByName(server.host.address), server.port)
    }

    new PreBuiltTransportClient(settings, pluginList).addTransportAddresses(addresses: _*)
  }
}
