package org.broadinstitute.dsde.firecloud.utils

import akka.actor.ActorSystem
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

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by davidan on 10/7/16.
  */
trait RestJsonClient extends FireCloudRequestBuilding {
  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext

  def unAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false) = {
    implicit val userInfo:WithAccessToken = null
    doRequest(None)(req, compressed, useFireCloudHeader)
  }
  def userAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false)(implicit userInfo: WithAccessToken): Future[HttpResponse] = doRequest(Option(addCredentials(userInfo.accessToken)))(req, compressed, useFireCloudHeader)
  def adminAuthedRequest(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false): Future[HttpResponse] = doRequest(Option(addAdminCredentials))(req, compressed, useFireCloudHeader)

  private def doRequest(addCreds: Option[RequestTransformer])(req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false): Future[HttpResponse] = {
    val pipeline = (compressed, useFireCloudHeader) match {
      case (true, true) => addFireCloudCredentials ~> addHeader (`Accept-Encoding`(gzip)) ~> sendReceive ~> decode(Gzip)
      case (true, false) => addHeader (`Accept-Encoding`(gzip)) ~> sendReceive ~> decode(Gzip)
      case (false, true) => addFireCloudCredentials ~> sendReceive
      case _ => sendReceive
    }

    val finalPipeline = addCreds.map(creds => creds ~> pipeline).getOrElse(pipeline)
    finalPipeline(req)
  }

  def authedRequestToObject[T](req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false)(implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    requestToObject(true, req, compressed, useFireCloudHeader)
  }

  def unAuthedRequestToObject[T](req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false)(implicit unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    implicit val userInfo:WithAccessToken = null
    requestToObject(false, req, compressed, useFireCloudHeader)
  }

  def requestToObject[T](auth: Boolean, req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false)(implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    val resp = if(auth) {
      userAuthedRequest(req, compressed, useFireCloudHeader)
    } else {
      unAuthedRequest(req, compressed, useFireCloudHeader)
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
