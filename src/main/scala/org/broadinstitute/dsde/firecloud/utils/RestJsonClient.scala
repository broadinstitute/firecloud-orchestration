package org.broadinstitute.dsde.firecloud.utils

import java.time.Instant
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders._
import akka.http.scaladsl.model.headers.{HttpEncodings, `Accept-Encoding`}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, ResponseEntity}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import spray.json.DeserializationException

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by davidan on 10/7/16.
  */
trait RestJsonClient extends FireCloudRequestBuilding with PerformanceLogging {
  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext
  implicit val materializer: Materializer
  val http = Http(system)

  private final val NoPerfLabel: Instant = Instant.MIN

  def unAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                      label: Option[String] = None): Future[HttpResponse] = {
    implicit val userInfo:WithAccessToken = null
    doRequest(None)(req, compressed, useFireCloudHeader, label)
  }

  def userAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                        label: Option[String] = None)
                       (implicit userInfo: WithAccessToken): Future[HttpResponse] =
    doRequest(Option(addCredentials(userInfo.accessToken)))(req, compressed, useFireCloudHeader, label)

  def adminAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                         label: Option[String] = None): Future[HttpResponse] =
    doRequest(Option(addAdminCredentials))(req, compressed, useFireCloudHeader, label)

  private def doRequest(addCreds: Option[RequestTransformer])(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                                                              label: Option[String] = None): Future[HttpResponse] = {
    val intermediateRequest = (compressed, useFireCloudHeader) match {
      case (true, true) => req.addHeader(`Accept-Encoding`(HttpEncodings.gzip)).addHeader(fireCloudHeader)
      case (true, false) => req.addHeader(`Accept-Encoding`(HttpEncodings.gzip))
      case (false, true) => req.addHeader(fireCloudHeader)
      case _ => req
    }

    val finalRequest = addCreds.map(creds => creds(intermediateRequest)).getOrElse(intermediateRequest)

    val tick = if (label.nonEmpty) Instant.now() else NoPerfLabel

    for {
      response <- http.singleRequest(finalRequest)
      decodedResponse <- if(compressed) Future.successful(decodeResponse(response)) else Future.successful(response)
    } yield {
      if (tick != NoPerfLabel) {
        val tock = Instant.now()
        perfLogger.info(perfmsg(label.get, decodedResponse.status.value, tick, tock))
      }
      decodedResponse
    }
  }

  def authedRequestToObject[T](req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                               label: Option[String] = None)
                              (implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[ResponseEntity, T], ers: ErrorReportSource): Future[T] = {
    requestToObject(true, req, compressed, useFireCloudHeader, label)
  }

  def unAuthedRequestToObject[T](req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                                 label: Option[String] = None)
                                (implicit unmarshaller: Unmarshaller[ResponseEntity, T], ers: ErrorReportSource): Future[T] = {
    implicit val userInfo:WithAccessToken = null
    requestToObject(false, req, compressed, useFireCloudHeader, label)
  }

  def adminAuthedRequestToObject[T](req:HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false)
                                   (implicit unmarshaller: Unmarshaller[ResponseEntity, T], ers: ErrorReportSource): Future[T] = {
    resultsToObject(adminAuthedRequest(req, compressed, useFireCloudHeader))
  }

  private def requestToObject[T](auth: Boolean, req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                                 label: Option[String] = None)
                                (implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[ResponseEntity, T], ers: ErrorReportSource): Future[T] = {
    val tick = if (label.nonEmpty) Instant.now() else NoPerfLabel

    val resp = if(auth) {
      userAuthedRequest(req, compressed, useFireCloudHeader)
    } else {
      unAuthedRequest(req, compressed, useFireCloudHeader)
    }

    resultsToObject(resp, label, tick)
  }

  private def resultsToObject[T](resp: Future[HttpResponse], label: Option[String] = None, tick: Instant = NoPerfLabel)
                                (implicit unmarshaller: Unmarshaller[ResponseEntity, T], ers: ErrorReportSource): Future[T] = {
    resp flatMap { response =>

      if (label.nonEmpty && tick != NoPerfLabel) {
        val tock = Instant.now()
        perfLogger.info(perfmsg(label.get, response.status.value, tick, tock))
      }

      response.status match {
        case s if s.isSuccess =>
          Unmarshal(response.entity).to[T].recoverWith {
            case de: DeserializationException =>
              throw new FireCloudExceptionWithErrorReport(
                ErrorReport(s"could not deserialize response: ${de.msg}"))
            case e: Throwable => {
              FCErrorReport(response).map { errorReport =>
                throw new FireCloudExceptionWithErrorReport(errorReport)
              }
            }
          }
        case f => {
          FCErrorReport(response).map { errorReport =>
            //we never consume the response body in this case, so we must discard the bytes here
            response.discardEntityBytes()
            throw new FireCloudExceptionWithErrorReport(errorReport)
          }
        }
      }
    }
  }

  private def decodeResponse(response: HttpResponse): HttpResponse = {
    val decoder = response.encoding match {
      case HttpEncodings.gzip => Gzip
      case HttpEncodings.deflate => Deflate
      case HttpEncodings.identity => NoCoding
      case _ => NoCoding
    }

    decoder.decodeMessage(response)
  }
}
