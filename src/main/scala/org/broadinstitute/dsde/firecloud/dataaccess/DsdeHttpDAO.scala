package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, HttpExt}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, ResponseEntity}
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.utils.{HttpClientUtils, HttpClientUtilsGzip, HttpClientUtilsStandard}
import org.broadinstitute.dsde.rawls.RawlsExceptionWithErrorReport
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 9/2/15.
  */
trait DsdeHttpDAO extends LazyLogging {
  protected implicit val system: ActorSystem
  protected implicit val materializer: Materializer
//  protected implicit val executionContext: ExecutionContext

  val http = Http(system)
  val httpClientUtils:HttpClientUtils = HttpClientUtilsStandard()

  protected def authHeader(userInfo: UserInfo): HttpHeader = authHeader(userInfo.accessToken)
  protected def authHeader(accessToken: OAuth2BearerToken): HttpHeader = Authorization(accessToken)

  protected def executeRequest[T](httpRequest: HttpRequest)(implicit um: Unmarshaller[ResponseEntity, T]): Future[T] = {
    httpClientUtils.executeRequestUnmarshalResponse[T](http, httpRequest)
  }

  protected def executeRequestAsUser[T](userInfo: UserInfo)(httpRequest: HttpRequest)(implicit um: Unmarshaller[ResponseEntity, T]): Future[T] = {
    httpClientUtils.executeRequestUnmarshalResponse[T](http, httpClientUtils.addHeader(httpRequest, authHeader(userInfo)))
  }

  protected def executeRequestWithToken[T](accessToken: OAuth2BearerToken)(httpRequest: HttpRequest)(implicit um: Unmarshaller[ResponseEntity, T]): Future[T] = {
    httpClientUtils.executeRequestUnmarshalResponse[T](http, httpClientUtils.addHeader(httpRequest, authHeader(accessToken)))
  }

  protected def executeRequestRaw[T](accessToken: OAuth2BearerToken)(httpRequest: HttpRequest)(implicit um: Unmarshaller[ResponseEntity, T]): Future[HttpResponse] = {
    httpClientUtils.executeRequest(http, httpClientUtils.addHeader(httpRequest, authHeader(accessToken)))
  }

  protected def executeRequestRawWithoutToken[T](httpRequest: HttpRequest)(implicit um: Unmarshaller[ResponseEntity, T]): Future[HttpResponse] = {
    httpClientUtils.executeRequest(http, httpRequest)
  }

  protected def pipeline[A](userInfo: UserInfo)(implicit um: Unmarshaller[ResponseEntity, A]) = executeRequestAsUser[A](userInfo) _

  protected def pipeline[A](implicit um: Unmarshaller[ResponseEntity, A]) = executeRequest[A] _

  protected def when500(throwable: Throwable ): Boolean = {
    throwable match {
      case t: RawlsExceptionWithErrorReport => t.errorReport.statusCode.exists(_.intValue/100 == 5)
      case _ => false
    }
  }

}
