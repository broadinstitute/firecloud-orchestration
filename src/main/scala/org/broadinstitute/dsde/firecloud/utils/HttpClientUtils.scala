package org.broadinstitute.dsde.firecloud.utils

import java.util.concurrent.TimeUnit

import akka.actor.ActorRefFactory
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{HttpEncodings, `Accept-Encoding`}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.rawls.RawlsExceptionWithErrorReport
import org.broadinstitute.dsde.rawls.model.ErrorReport

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

/**
  * Created by rtitle on 7/7/17.
  */
trait HttpClientUtils extends LazyLogging {
  implicit val materializer: Materializer
  implicit val executionContext: ExecutionContext

  def addHeader(httpRequest: HttpRequest, header: HttpHeader) = {
    httpRequest.copy(headers = httpRequest.headers :+ header)
  }

  def executeRequest(http: HttpExt, httpRequest: HttpRequest): Future[HttpResponse] = http.singleRequest(httpRequest)

  def executeRequestUnmarshalResponse[T](http: HttpExt, httpRequest: HttpRequest)(implicit unmarshaller: Unmarshaller[ResponseEntity, T]): Future[T] = {
    executeRequest(http, httpRequest) recover { case t: Throwable =>
      throw new RawlsExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, s"http call failed: ${httpRequest.uri}: ${t.getMessage}", t))
    } flatMap { response =>
      if (response.status.isSuccess) {
        Unmarshal(response.entity).to[T]
      } else {
        Unmarshal(response.entity).to[String] map { entityAsString =>
          logger.debug(s"http error status ${response.status} calling uri ${httpRequest.uri}, response: ${entityAsString}")
          val message = if (response.status == StatusCodes.Unauthorized)
            s"The service indicated that this call was unauthorized. " +
              s"If you believe this is a mistake, please try your request again. " +
              s"Error occurred calling uri ${httpRequest.uri}"
          else
            s"http error calling uri ${httpRequest.uri}"
          throw new FireCloudExceptionWithErrorReport(ErrorReport(response.status, message))
        }
      }
    }
  }

  def executeRequestUnmarshalResponseAcceptNoContent[T](http: HttpExt, httpRequest: HttpRequest)(implicit unmarshaller: Unmarshaller[ResponseEntity, T]): Future[Option[T]] = {
    executeRequest(http, httpRequest) recover { case t: Throwable =>
      throw new RawlsExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, s"http call failed: ${httpRequest.uri}: ${t.getMessage}", t))
    } flatMap { response =>
      if (response.status.isSuccess) {
        if (response.status == StatusCodes.NoContent) {
          // don't know if discarding the entity is required if there is no content but it probably doesn't hurt
          response.entity.discardBytes()
          Future.successful(None)
        } else {
          Unmarshal(response.entity).to[T].map(Option(_))
        }
      } else {
        Unmarshal(response.entity).to[String] map { entityAsString =>
          logger.debug(s"http error status ${response.status} calling uri ${httpRequest.uri}, response: ${entityAsString}")
          throw new FireCloudExceptionWithErrorReport(ErrorReport(response.status, s"http error calling uri ${httpRequest.uri}"))
        }
      }
    }
  }
}

case class HttpClientUtilsStandard()(implicit val materializer: Materializer, val executionContext: ExecutionContext) extends HttpClientUtils

case class HttpClientUtilsGzip()(implicit val materializer: Materializer, val executionContext: ExecutionContext) extends HttpClientUtils {
  override def executeRequest(http: HttpExt, httpRequest: HttpRequest): Future[HttpResponse] = {
    http.singleRequest(addHeader(httpRequest, `Accept-Encoding`(HttpEncodings.gzip))).map { response =>
      Gzip.decodeMessage(response)
    }
  }
}
