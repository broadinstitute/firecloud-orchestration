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
trait RestJsonClient extends FireCloudRequestBuilding {
  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext

  def unAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None): Future[HttpResponse] = {
    implicit val userInfo:WithAccessToken = null
    doRequest(None)(req, compressed, useFireCloudHeader, connector)
  }

  def userAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None)(implicit userInfo: WithAccessToken): Future[HttpResponse] =
    doRequest(Option(addCredentials(userInfo.accessToken)))(req, compressed, useFireCloudHeader, connector)

  def adminAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None): Future[HttpResponse] =
    doRequest(Option(addAdminCredentials))(req, compressed, useFireCloudHeader, connector)

  private def doRequest(addCreds: Option[RequestTransformer])(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None): Future[HttpResponse] = {
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
    finalPipeline(req)
  }

  def authedRequestToObject[T](req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None)(implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    requestToObject(true, req, compressed, useFireCloudHeader, connector)
  }

  def unAuthedRequestToObject[T](req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None)(implicit unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    implicit val userInfo:WithAccessToken = null
    requestToObject(false, req, compressed, useFireCloudHeader, connector)
  }

  def requestToObject[T](auth: Boolean, req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None)(implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    val resp = if(auth) {
      userAuthedRequest(req, compressed, useFireCloudHeader, connector)
    } else {
      unAuthedRequest(req, compressed, useFireCloudHeader, connector)
    }

    resp map { response =>
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
