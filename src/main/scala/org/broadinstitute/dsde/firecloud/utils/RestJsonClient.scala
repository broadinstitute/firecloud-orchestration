package org.broadinstitute.dsde.firecloud.utils

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.{ErrorReport, UserInfo}
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

  def userAuthedRequest(req: HttpRequest, compressed: Boolean = false)(implicit userInfo: UserInfo): Future[HttpResponse] = {
    val pipeline = if (compressed) {
      addCredentials(userInfo.accessToken) ~> addHeader (`Accept-Encoding`(gzip)) ~> sendReceive ~> decode(Gzip)
    } else {
      addCredentials(userInfo.accessToken) ~> sendReceive
    }
    pipeline(req)
  }

  def requestToObject[T](req: HttpRequest, compressed: Boolean = false)(implicit userInfo: UserInfo, unmarshaller: Unmarshaller[T]): Future[T] = {
    userAuthedRequest( req, compressed ) map {response =>
      response.status match {
        case s if s.isSuccess =>
          response.entity.as[T] match {
            case Right(obj) => obj
            case Left(error) => throw new FireCloudExceptionWithErrorReport(ErrorReport(response))
          }
        case f => throw new FireCloudExceptionWithErrorReport(ErrorReport(response))
      }
    }
  }
}
