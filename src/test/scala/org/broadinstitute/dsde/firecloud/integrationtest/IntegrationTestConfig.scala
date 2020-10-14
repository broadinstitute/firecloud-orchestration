package org.broadinstitute.dsde.firecloud.integrationtest

import java.io.File

import akka.http.scaladsl.model.Uri.Authority
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.firecloud.FireCloudConfig

trait IntegrationTestConfig {
  val unitTestConf = ConfigFactory.load()
  val runtimeConf = ConfigFactory.parseFile(new File("config/firecloud-orchestration.conf"))
  val itTestConf = ConfigFactory
      .parseFile(new File("src/test/resources/ittest.conf"))
      .withFallback(runtimeConf)
      .withFallback(unitTestConf)

  object ITElasticSearch {
    // the list of ES servers to use for integration tests looks in this order:
    //  - as a system property named esurls. Note that without changes to sbt config, this cannot be passed
    //      via the -D flag on the command line, since we fork tests into their own JVM.
    //  - in the ESURLS environment variable, which is what Jenkins does
    //  - in src/test/resources/ittest.conf, which doesn't normally exist unless you put it there
    //  - in config/firecloud-orchestration.conf, which usually exists for developers but won't exist in Jenkins
    //  - in standard test conf, typically src/test/resources/reference.conf, which is unlikely to have real server urls
    private val envESUrls = System.getenv.getOrDefault("ESURLS", itTestConf.getConfig("elasticsearch").getString("urls"))

    private val systemESUrls = System.getProperty("esurls", envESUrls)

    val servers: Seq[Authority] = FireCloudConfig.parseESServers(systemESUrls)
    val clusterName: String = itTestConf.getConfig("elasticsearch").getString("clusterName")
  }

}
