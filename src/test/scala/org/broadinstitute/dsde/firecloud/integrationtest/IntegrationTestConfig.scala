package org.broadinstitute.dsde.firecloud.integrationtest

import java.io.File

import akka.http.scaladsl.model.Uri.Authority
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.firecloud.FireCloudConfig

trait IntegrationTestConfig {

  val itTestConf = ConfigFactory.load()

  object ITElasticSearch {
    private val serverUrls = itTestConf.getConfig("elasticsearch").getString("urls")

    val servers: Seq[Authority] = FireCloudConfig.parseESServers(serverUrls)

    val clusterName: String = itTestConf.getConfig("elasticsearch").getString("clusterName")
  }

}
