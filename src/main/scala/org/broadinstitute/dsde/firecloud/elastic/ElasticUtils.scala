package org.broadinstitute.dsde.firecloud.elastic

import akka.http.scaladsl.model.Uri.Authority
import org.apache.http.HttpHost
import org.elasticsearch.client.{RestClient, RestHighLevelClient}

object ElasticUtils {
  // TODO: AJ-249 is clusterName unused?
  def buildClient(servers:Seq[Authority], clusterName: String): RestHighLevelClient = {
    val addresses = servers map { server =>
      new HttpHost(server.host.address(), server.port, "http")
    }

    new RestHighLevelClient(RestClient.builder(addresses:_*).)
  }
}
