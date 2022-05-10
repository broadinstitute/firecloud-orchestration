package org.broadinstitute.dsde.firecloud.elastic

import akka.http.scaladsl.model.Uri.{Authority, Host}
import org.apache.http.HttpHost
import org.elasticsearch.client.{RestClient, RestHighLevelClient}

object ElasticUtils {
  // TODO: AJ-249 is clusterName unused?
  def buildClient(servers:Seq[Authority], clusterName: String): RestHighLevelClient = {

    val hardcodedForNow: Seq[Authority] = Seq(Authority(host = Host("34.134.204.3"), port = 9200))

    // TODO: use servers from argument instead of hardcoded values
    val addresses = hardcodedForNow map { server =>
      new HttpHost(server.host.address(), server.port, "http")
    }

    new RestHighLevelClient(RestClient.builder(addresses:_*))
  }
}
