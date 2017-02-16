package org.broadinstitute.dsde.firecloud.integrationtest

import java.io.File

import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import spray.http.Uri.Authority

trait IntegrationTestConfig {
  val unitTestConf = ConfigFactory.load()
  val itTestConf = ConfigFactory.parseFile(new File("config/firecloud-orchestration.conf"))

  object ITElasticSearch {
    private val elasticsearch = itTestConf.getConfig("elasticsearch")
    val servers: Seq[Authority] = FireCloudConfig.parseESServers(elasticsearch.getString("urls"))
  }

}
