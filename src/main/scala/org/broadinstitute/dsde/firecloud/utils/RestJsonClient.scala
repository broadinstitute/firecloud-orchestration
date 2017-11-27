package org.broadinstitute.dsde.firecloud.utils

import akka.actor.{ActorRef, ActorSystem}
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import spray.client.pipelining._
import spray.http.HttpEncodings._
import spray.http.HttpHeaders.`Accept-Encoding`
import spray.http.{HttpRequest, HttpResponse}
import spray.httpx.encoding.Gzip
import spray.httpx.unmarshalling._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by davidan on 10/7/16.
  */
trait RestJsonClient extends FireCloudRequestBuilding with PerformanceLogging {
  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext

  private final val NoPerfLabel: Long = -1

  def unAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None, label: Option[String] = None): Future[HttpResponse] = {
    implicit val userInfo:WithAccessToken = null
    doRequest(None)(req, compressed, useFireCloudHeader, connector, label)
  }

  def userAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None, label: Option[String] = None)(implicit userInfo: WithAccessToken): Future[HttpResponse] =
    doRequest(Option(addCredentials(userInfo.accessToken)))(req, compressed, useFireCloudHeader, connector, label)

  def adminAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None, label: Option[String] = None): Future[HttpResponse] =
    doRequest(Option(addAdminCredentials))(req, compressed, useFireCloudHeader, connector, label)

  private def doRequest(addCreds: Option[RequestTransformer])(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None, label: Option[String] = None): Future[HttpResponse] = {
    val sr = if (connector.isDefined) {
      sendReceive(connector.get)(executionContext, futureTimeout = 60.seconds)
    } else {
      sendReceive
    }

    val pipeline = (compressed, useFireCloudHeader) match {
      case (true, true) => addFireCloudCredentials ~> addHeader (`Accept-Encoding`(gzip)) ~> sr ~> decode(Gzip)
      case (true, false) => addHeader (`Accept-Encoding`(gzip)) ~> sr ~> decode(Gzip)
      case (false, true) => addFireCloudCredentials ~> sr
      case _ => sr
    }

    val finalPipeline = addCreds.map(creds => creds ~> pipeline).getOrElse(pipeline)

    val tick = if (label.nonEmpty) System.currentTimeMillis() else NoPerfLabel

    finalPipeline(req) map { res =>
      if (tick != NoPerfLabel) {
        val elapsed = System.currentTimeMillis() - tick
        perfLogger.info(perfmsg(label.get, elapsed))
      }

      res
    }
  }

  def authedRequestToObject[T](req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None, label: Option[String] = None)(implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    requestToObject(true, req, compressed, useFireCloudHeader, connector, label)
  }

  def unAuthedRequestToObject[T](req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None, label: Option[String] = None)(implicit unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    implicit val userInfo:WithAccessToken = null
    requestToObject(false, req, compressed, useFireCloudHeader, connector, label)
  }

  // zero usages in the codebase
  def adminAuthedRequestToObject[T](req:HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false)(implicit unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    resultsToObject(adminAuthedRequest(req, compressed, useFireCloudHeader))
  }

  private def requestToObject[T](auth: Boolean, req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None, label: Option[String] = None)(implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    val tick = if (label.nonEmpty) System.currentTimeMillis() else NoPerfLabel

    val resp = if(auth) {
      userAuthedRequest(req, compressed, useFireCloudHeader, connector)
    } else {
      unAuthedRequest(req, compressed, useFireCloudHeader, connector)
    }
    resultsToObject(resp, label, tick)
  }

  private def resultsToObject[T](resp: Future[HttpResponse], label: Option[String] = None, tick: Long = NoPerfLabel)(implicit unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    resp map { response =>

      if (label.nonEmpty && tick != NoPerfLabel) {
        val elapsed = System.currentTimeMillis() - tick
        perfLogger.info(perfmsg(label.get, elapsed))
      }

      response.status match {
        case s if s.isSuccess =>
          response.entity.as[T] match {
            case Right(obj) => obj
            case Left(error) => throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
          }
        case f => throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
      }
    }
  }
}
