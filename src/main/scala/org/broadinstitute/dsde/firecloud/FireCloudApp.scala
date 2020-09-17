package org.broadinstitute.dsde.firecloud

import scala.concurrent.duration._
import akka.actor.{ActorSystem, Props}
import com.typesafe.scalalogging.LazyLogging
import akka.http.scaladsl.Http

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import akka.stream.ActorMaterializer

import org.broadinstitute.dsde.firecloud.dataaccess.{AgoraDAO, ConsentDAO, ESResearchPurposeSupport, ElasticSearchDAO, ElasticSearchOntologyDAO, ElasticSearchShareLogDAO, GoogleServicesDAO, HttpAgoraDAO, HttpConsentDAO, HttpGoogleServicesDAO, HttpLogitDAO, HttpRawlsDAO, HttpSamDAO, HttpThurloeDAO, LogitDAO, NoopLogitDAO, OntologyDAO, RawlsDAO, ResearchPurposeSupport, SamDAO, SearchDAO, ShareLogDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.elastic.ElasticUtils
import org.elasticsearch.client.transport.TransportClient

object FireCloudApp extends App with LazyLogging {

  // we need an ActorSystem to host our application in
  val system = ActorSystem("FireCloud-Orchestration-API")
  val timeoutDuration = FiniteDuration(FireCloudConfig.HttpConfig.timeoutSeconds, SECONDS)

  private def startup(): Unit = {
    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("FireCloud-Orchestration-API")
    implicit val materializer = ActorMaterializer()

    val elasticSearchClient: TransportClient = ElasticUtils.buildClient(FireCloudConfig.ElasticSearch.servers, FireCloudConfig.ElasticSearch.clusterName)
    val logitMetricsEnabled = FireCloudConfig.Metrics.logitApiKey.isDefined

    val agoraDAO:AgoraDAO = new HttpAgoraDAO(FireCloudConfig.Agora)
    val rawlsDAO:RawlsDAO = new HttpRawlsDAO
    val samDAO:SamDAO = new HttpSamDAO
    val thurloeDAO:ThurloeDAO = new HttpThurloeDAO
    val googleServicesDAO:GoogleServicesDAO = HttpGoogleServicesDAO
    val ontologyDAO:OntologyDAO = new ElasticSearchOntologyDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.ontologyIndexName)
    val consentDAO:ConsentDAO = new HttpConsentDAO
    val researchPurposeSupport:ResearchPurposeSupport = new ESResearchPurposeSupport(ontologyDAO)
    val searchDAO:SearchDAO = new ElasticSearchDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.indexName, researchPurposeSupport)
    val logitDAO:LogitDAO = if (logitMetricsEnabled)
      new HttpLogitDAO(FireCloudConfig.Metrics.logitUrl, FireCloudConfig.Metrics.logitApiKey.get)
    else
      new NoopLogitDAO
    val shareLogDAO:ShareLogDAO = new ElasticSearchShareLogDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.shareLogIndexName)


    val service = new FireCloudApiService (

    )

    for {
      _ <- Http().bindAndHandle(service.route, "0.0.0.0", 8080) recover {
        case t: Throwable =>
          logger.error("FATAL - failure starting http server", t)
          throw t
      }

    } yield {

    }
  }

  startup()
}
