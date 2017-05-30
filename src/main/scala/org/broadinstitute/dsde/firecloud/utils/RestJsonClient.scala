package org.broadinstitute.dsde.firecloud.utils

import akka.actor.{ActorRef, ActorSystem}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import spray.can.Http
import spray.can.Http.HostConnectorSetup
import spray.client.pipelining._
import spray.http.HttpEncodings._
import spray.http.HttpHeaders.`Accept-Encoding`
import spray.http.{HttpRequest, HttpResponse, Uri}
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

  def adminAuthedRequestToObject[T](req:HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false)(implicit unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    resultsToObject(adminAuthedRequest(req, compressed, useFireCloudHeader))
  }

  def requestToObject[T](auth: Boolean, req: HttpRequest, compressed: Boolean = false, useFireCloudHeader: Boolean = false, connector: Option[ActorRef] = None)(implicit userInfo: WithAccessToken, unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
    val resp = if(auth) {
      userAuthedRequest(req, compressed, useFireCloudHeader, connector)
    } else {
      unAuthedRequest(req, compressed, useFireCloudHeader, connector)
    }
    resultsToObject(resp)
  }

  def resultsToObject[T](resp: Future[HttpResponse])(implicit unmarshaller: Unmarshaller[T], ers: ErrorReportSource): Future[T] = {
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

  private def getHostConnectorSetup(address: String): HostConnectorSetup = {
    val uri = Uri(address)
    val (port, sslEncryption) = (uri.authority.port, uri.scheme) match {
      case (0, "https") => (443, true)
      case (0, "http") => (80, false)
      case (port:Int, "https") => (port, true)
      case (port:Int, "http") => (port, false)
      case _ => throw new FireCloudException(s"Could not parse rawlsUri: ${address}")
    }
    Http.HostConnectorSetup(uri.authority.host.address, port, sslEncryption)
  }

  protected final def getHostConnector(address: String): Future[ActorRef] = {
    implicit val timeout:Timeout = 60.seconds // timeout to get the host connector reference
    val hostConnectorSetup = getHostConnectorSetup(address)
    for (Http.HostConnectorInfo(connector, _) <- IO(Http) ? hostConnectorSetup) yield connector
  }

}
