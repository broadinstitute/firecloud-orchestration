package org.broadinstitute.dsde.firecloud.integrationtest

import java.io.File

import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import spray.http.Uri.Authority

trait IntegrationTestConfig {
  val unitTestConf = ConfigFactory.load()
  val runtimeConf = ConfigFactory.parseFile(new File("config/firecloud-orchestration.conf"))
  val itTestConf = ConfigFactory
      .parseFile(new File("src/test/resources/ittest.conf"))
      .withFallback(runtimeConf)
      .withFallback(unitTestConf)

  object ITElasticSearch {
    // the list of ES servers to use for integration tests looks in this order:
    //  - passed on command-line, e.g. "sbt -Desurls=foo it:test", which is what Jenkins does
    //  - in src/test/resources/ittest.conf, which doesn't normally exist unless you put it there
    //  - in config/firecloud-orchestration.conf, which usually exists for developers but won't exist in Jenkins
    //  - in standard test conf, typically src/test/resources/reference.conf, which is unlikely to have real server urls
    private val systemESUrls = System.getProperty("esurls",
        itTestConf.getConfig("elasticsearch").getString("urls"))

    val servers: Seq[Authority] = FireCloudConfig.parseESServers(systemESUrls)
  }

}
