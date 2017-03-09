package org.broadinstitute.dsde.firecloud.utils

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
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
trait RestJsonClient {

  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext

  def unAuthedRequest(req: HttpRequest, compressed: Boolean = false): Future[HttpResponse] = {
    implicit val userInfo:WithAccessToken = null
    doRequest(req, compressed, false)
  }

  def userAuthedRequest(req: HttpRequest, compressed: Boolean = false)(implicit userInfo: WithAccessToken): Future[HttpResponse] = {
    doRequest(req, compressed, true)
  }

  private def doRequest(req: HttpRequest, compressed: Boolean, authed: Boolean)(implicit userInfo: WithAccessToken): Future[HttpResponse] = {
    val basePipeline = if (compressed) {
      addHeader(`Accept-Encoding`(gzip)) ~> sendReceive ~> decode(Gzip)
    } else {
      sendReceive
    }
    val finalPipeline = if (authed) {
      addCredentials(userInfo.accessToken) ~> basePipeline
    } else {
      basePipeline
    }
    finalPipeline(req)
  }

  def authedRequestToObject[T](req: HttpRequest, compressed: Boolean = false)(implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[T]): Future[T] = {
    requestToObject(true, req, compressed)
  }

  def unAuthedRequestToObject[T](req: HttpRequest, compressed: Boolean = false)(implicit unmarshaller: Unmarshaller[T]): Future[T] = {
    implicit val userInfo:WithAccessToken = null
    requestToObject(false, req, compressed)
  }

  private def requestToObject[T](auth: Boolean, req: HttpRequest, compressed: Boolean = false)(implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[T]): Future[T] = {
    val resp = if (auth) {
      userAuthedRequest(req, compressed)
    } else {
      unAuthedRequest(req, compressed)
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
