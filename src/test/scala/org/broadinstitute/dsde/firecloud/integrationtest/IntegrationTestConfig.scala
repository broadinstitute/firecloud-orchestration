package org.broadinstitute.dsde.firecloud.integrationtest

import java.io.File

import com.typesafe.config.ConfigFactory
import org.parboiled.common.FileUtils
import spray.http.Uri.{Authority, Host}

import scala.collection.JavaConversions._

trait IntegrationTestConfig {
  val unitTestConf = ConfigFactory.load()
  val itTestConf = ConfigFactory.parseFile(new File("src/test/resources/integrationtest.conf"))

  object ITElasticSearch {
    private val elasticsearch = itTestConf.getConfig("elasticsearch")
    val servers: Seq[Authority] = elasticsearch.getString("urls").split(',') map { hostport =>
      val hp = hostport.split(':')
      Authority(Host(hp(0)), hp(1).toInt)
    }
  }


}
