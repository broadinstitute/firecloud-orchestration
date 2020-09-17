package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.metrics.MetricsException
import org.broadinstitute.dsde.firecloud.model.Metrics
import org.broadinstitute.dsde.firecloud.model.Metrics.LogitMetric
import org.broadinstitute.dsde.firecloud.model.MetricsFormat.LogitMetricFormat
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, MediaTypes, StatusCodes}

import scala.concurrent.{ExecutionContext, Future}

class HttpLogitDAO(logitUrl: String, logitApiKey: String)
                  (implicit val system: ActorSystem, executionContext: ExecutionContext) extends LogitDAO with SprayJsonSupport with LazyLogging {

  val http = Http(system)

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
    val httpRequest = HttpRequest(
      method = HttpMethods.PUT,
      uri = logitUrl,
      headers = List(RawHeader("LogType", Metrics.LOG_TYPE), RawHeader("ApiKey", logitApiKey)),
      entity = metric
    )

    http.singleRequest(httpRequest) map { logitResponse =>
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
