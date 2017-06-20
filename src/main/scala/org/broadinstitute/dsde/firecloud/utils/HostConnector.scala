package org.broadinstitute.dsde.firecloud.utils

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.io.IO
import akka.util.Timeout
import org.broadinstitute.dsde.firecloud.FireCloudException
import spray.can.Http
import spray.can.Http.HostConnectorSetup
import spray.http.Uri

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait HostConnector {
  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext

  private def getHostConnectorSetup(address: String): HostConnectorSetup = {
    val uri = Uri(address)
    val (port, sslEncryption) = (uri.authority.port, uri.scheme) match {
      case (0, "https") => (443, true)
      case (0, "http") => (80, false)
      case (port:Int, "https") => (port, true)
      case (port:Int, "http") => (port, false)
      case _ => throw new FireCloudException(s"Could not parse address: $address")
    }
    Http.HostConnectorSetup(uri.authority.host.address, port, sslEncryption)
  }

  final def getHostConnector(address: String): Future[ActorRef] = {
    implicit val timeout:Timeout = 60.seconds // timeout to get the host connector reference
    val hostConnectorSetup = getHostConnectorSetup(address)
    for (Http.HostConnectorInfo(connector, _) <- IO(Http) ? hostConnectorSetup) yield connector
  }

}
