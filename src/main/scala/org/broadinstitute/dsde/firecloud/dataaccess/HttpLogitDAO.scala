package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.javadsl.model.HttpHeader
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{OAuth2BearerToken, RawHeader}
import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.metrics.MetricsException
import org.broadinstitute.dsde.firecloud.model.Metrics
import org.broadinstitute.dsde.firecloud.model.Metrics.LogitMetric
import org.broadinstitute.dsde.firecloud.model.MetricsFormat.LogitMetricFormat
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.utils.{HttpClientUtilsStandard, RestJsonClient}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class HttpLogitDAO(logitUrl: String, logitApiKey: String)
                  (implicit val system: ActorSystem, val materializer: Materializer, implicit val executionContext: ExecutionContext) extends LogitDAO with SprayJsonSupport with LazyLogging with RestJsonClient with FireCloudRequestBuilding {
  /*
    Requirements to send to Logit:
      - Valid JSON content
      - API key (find this on your dashboard) sent in the headers
      - Content-Type header set as application/json
      - Either POST or PUT to https://api.logit.io/v2

    curl -i -H "ApiKey: your-api-key" -i -H "Content-Type: application/json" -H "LogType: SampleHttpLog" https://api.logit.io/v2 -d '{"test":"test","example": { "a": 1, "b": 2 } }'

    Response
      - You should expect to receive a 202 Accepted response code for a successful message
  */

  override def recordMetric(metric: LogitMetric): Future[LogitMetric] = {

    val logType: HttpHeader = RawHeader("LogType", Metrics.LOG_TYPE)
    val apiKey: HttpHeader = RawHeader("ApiKey", logitApiKey)

    val request = Put(logitUrl, metric)
      .addHeaders(List(logType, apiKey).asJava)

    unAuthedRequest(request).map { logitResponse =>
      logitResponse.status match {
        case Accepted =>
          logger.debug(s"Logit request succeeded: ${LogitMetricFormat.write(metric).compactPrint}")
          metric
        case _ =>
          throw new MetricsException(s"Logit returned ${logitResponse.status} on metrics request: $logitResponse")
      }
    } recover {
      case e:Exception =>
        throw new MetricsException(s"Exception calling logit: ${e.getMessage}", e)
    }
  }

}
