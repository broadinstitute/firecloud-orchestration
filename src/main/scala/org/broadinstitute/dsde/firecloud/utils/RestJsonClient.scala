package org.broadinstitute.dsde.firecloud.utils

import java.time.Instant

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model.TransferEncodings.gzip
import akka.http.scaladsl.model.headers.{HttpEncodings, `Accept-Encoding`}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, ResponseEntity}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
//import spray.client.pipelining._
//import spray.http.HttpEncodings._
//import spray.http.HttpHeaders.`Accept-Encoding`
//import spray.http.{HttpRequest, HttpResponse}
//import spray.httpx.encoding.Gzip
//import spray.httpx.unmarshalling._

import scala.concurrent.duration._
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
                      connector: Option[ActorRef] = None, label: Option[String] = None): Future[HttpResponse] = {
    implicit val userInfo:WithAccessToken = null
    doRequest(None)(req, compressed, useFireCloudHeader, connector, label)
  }

  def userAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                        connector: Option[ActorRef] = None, label: Option[String] = None)
                       (implicit userInfo: WithAccessToken): Future[HttpResponse] =
    doRequest(Option(addCredentials(userInfo.accessToken)))(req, compressed, useFireCloudHeader, connector, label)

  def adminAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                         connector: Option[ActorRef] = None, label: Option[String] = None): Future[HttpResponse] =
    doRequest(Option(addAdminCredentials))(req, compressed, useFireCloudHeader, connector, label)

  private def doRequest(addCreds: Option[RequestTransformer])(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                                                              connector: Option[ActorRef] = None, label: Option[String] = None): Future[HttpResponse] = {
    //    val sr = if (connector.isDefined) {
    //      sendReceive(connector.get)(executionContext, futureTimeout = 60.seconds)
    //    } else {
    //      sendReceive
    //    }
    //TODO: ask David An about the above connector code

    val intermediateRequest = (compressed, useFireCloudHeader) match {
      case (true, true) => req.addHeader(`Accept-Encoding`(HttpEncodings.gzip)).addHeader(fireCloudHeader)
      case (true, false) => req.addHeader(`Accept-Encoding`(HttpEncodings.gzip))
      case (false, true) => req.addHeader(fireCloudHeader)
      case _ => req
    }

    val finalRequest = addCreds.map(creds => creds(intermediateRequest)).getOrElse(intermediateRequest)

    val tick = if (label.nonEmpty) Instant.now() else NoPerfLabel

    http.singleRequest(finalRequest) map { res =>
      if (tick != NoPerfLabel) {
        val tock = Instant.now()
        perfLogger.info(perfmsg(label.get, res.status.value, tick, tock))
      }
      if(compressed) res else res //TODO: actually decode message somewhere
    }
  }

  def authedRequestToObject[T](req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                               connector: Option[ActorRef] = None, label: Option[String] = None)
                              (implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[ResponseEntity, T], ers: ErrorReportSource): Future[T] = {
    requestToObject(true, req, compressed, useFireCloudHeader, connector, label)
  }

  def unAuthedRequestToObject[T](req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                                 connector: Option[ActorRef] = None, label: Option[String] = None)
                                (implicit unmarshaller: Unmarshaller[ResponseEntity, T], ers: ErrorReportSource): Future[T] = {
    implicit val userInfo:WithAccessToken = null
    requestToObject(false, req, compressed, useFireCloudHeader, connector, label)
  }

  def adminAuthedRequestToObject[T](req:HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false)
                                   (implicit unmarshaller: Unmarshaller[ResponseEntity, T], ers: ErrorReportSource): Future[T] = {
    resultsToObject(adminAuthedRequest(req, compressed, useFireCloudHeader))
  }

  private def requestToObject[T](auth: Boolean, req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false,
                                 connector: Option[ActorRef] = None, label: Option[String] = None)
                                (implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[ResponseEntity, T], ers: ErrorReportSource): Future[T] = {
    val tick = if (label.nonEmpty) Instant.now() else NoPerfLabel

    val resp = if(auth) {
      userAuthedRequest(req, compressed, useFireCloudHeader, connector)
    } else {
      unAuthedRequest(req, compressed, useFireCloudHeader, connector)
    }
    resultsToObject(resp, label, tick)
  }

  private def resultsToObject[T](resp: Future[HttpResponse], label: Option[String] = None, tick: Instant = NoPerfLabel)
                                (implicit unmarshaller: Unmarshaller[ResponseEntity, T], ers: ErrorReportSource): Future[T] = {
    resp map { response =>

      if (label.nonEmpty && tick != NoPerfLabel) {
        val tock = Instant.now()
        perfLogger.info(perfmsg(label.get, response.status.value, tick, tock))
      }

      response.status match {
        case s if s.isSuccess =>
          Unmarshal(response.entity).to[T] match {
            case Right(obj) => obj
            case Left(error) => throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
          }
        case f => throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
      }
    }
  }
}
