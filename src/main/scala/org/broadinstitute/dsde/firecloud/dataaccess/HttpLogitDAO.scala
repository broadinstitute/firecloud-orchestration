package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorRefFactory
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.metrics.MetricsException
import org.broadinstitute.dsde.firecloud.model.Metrics
import org.broadinstitute.dsde.firecloud.model.Metrics.LogitMetric
import org.broadinstitute.dsde.firecloud.model.MetricsFormat.LogitMetricFormat
import spray.client.pipelining._
import spray.http.HttpHeaders.RawHeader
import spray.http.StatusCodes.Accepted
import spray.httpx.SprayJsonSupport

import scala.concurrent.{ExecutionContext, Future}

class HttpLogitDAO(logitUrl: String, logitApiKey: String)
                  (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext) extends LogitDAO with SprayJsonSupport with LazyLogging {

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
    val pipeline = Put(logitUrl, metric) ~>
      addHeader(RawHeader("LogType", Metrics.LOG_TYPE)) ~>
      addHeader(RawHeader("ApiKey", logitApiKey)) ~>
      sendReceive

    pipeline map { logitResponse =>
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
