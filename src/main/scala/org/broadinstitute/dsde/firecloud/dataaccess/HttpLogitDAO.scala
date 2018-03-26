package org.broadinstitute.dsde.firecloud.dataaccess
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.Metrics.LogitMetric
import org.broadinstitute.dsde.firecloud.model.MetricsFormat.LogitMetricFormat
import spray.client.pipelining._
import spray.http.{HttpHeader, HttpHeaders}
import spray.http.HttpHeaders.RawHeader
import spray.http.MediaTypes.`application/json`
import spray.http.StatusCodes.Accepted

class HttpLogitDAO(logitUrlOption: Option[String], logitApiKeyOption: Option[String]) extends LogitDAO with LazyLogging {

  private val enabled = logitUrlOption.isDefined && logitApiKeyOption.isDefined
  private val logitUrl = logitUrlOption.getOrElse("")
  private val logitApiKey = logitApiKeyOption.getOrElse("")

  // TODO: accept url and apiKey
  // TODO: disable everything if no apiKey



  /*
Requirements
Valid JSON content
API key (find this on your dashboard) sent in the headers
Content-Type header set as application/json
Either POST or PUT to https://api.logit.io/v2

curl -i -H "ApiKey: your-api-key" -i -H "Content-Type: application/json" -H "LogType: SampleHttpLog" https://api.logit.io/v2 -d '{"test":"test","example": { "a": 1, "b": 2 } }'

Response
You should expect to receive a 202 Accepted response code for a successful message
 */

  override def recordMetric(logtype: String, metric: LogitMetric): Unit = {
    if (enabled) {
      val pipeline = Put(logitUrl, metric) ~> addHeader(RawHeader("ApiKey", logitApiKey)) ~> addHeader(HttpHeaders.`Content-Type`(`application/json`)) ~> sendReceive
      pipeline map { logitResponse =>
        logitResponse.status match {
          case Accepted => logger.info("Logit request succeeded!")
          case _ => logger.error(s"Logit request returned: $logitResponse")
        }
      } recover {
        case e:Exception => logger.error(s"Exception calling logit: ${e.getMessage}")
      }

    }
  }
}
