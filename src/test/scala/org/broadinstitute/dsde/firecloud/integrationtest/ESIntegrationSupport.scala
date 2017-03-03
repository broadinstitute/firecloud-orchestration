package org.broadinstitute.dsde.firecloud.integrationtest

import java.text.SimpleDateFormat
import java.util.Calendar

import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{ElasticSearchDAO, SearchDAO}
import org.broadinstitute.dsde.firecloud.model.LibrarySearchParams

import scala.util.{Failure, Success, Try}

/**
  * Created by davidan on 2/9/17.
  */
object ESIntegrationSupport extends IntegrationTestConfig {

  // generate a unique, timestamped index name that is obvious to users it was generated by unit tests
  lazy val itTestIndexName = {
    val tag = "ittest"
    val timeStr = new SimpleDateFormat("yyyyMMdd't'HH:mm:ss").format(Calendar.getInstance.getTime)
    val username = Try(System.getProperty("user.name")) match {
      case Success(str) => str.toLowerCase
      case Failure(ex) => "unknownuser"
    }
    val hostname = Try(java.net.InetAddress.getLocalHost.getHostName) match {
      case Success(str) => str.toLowerCase
      case Failure(ex) => "unknownhostname"
    }

    Seq(tag, username, hostname, timeStr).mkString("_")
  }

  lazy val searchDAO:SearchDAO = {
    // construct a dao, using IntegrationTestConfig's server names (which should be the runtime server names)
    // and the index name defined above
    new ElasticSearchDAO(ITElasticSearch.servers, itTestIndexName)
  }

  lazy val emptyCriteria = LibrarySearchParams(None,Map.empty[String,Seq[String]],Map.empty[String,Int],None,None,None,None)

}
