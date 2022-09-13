package org.broadinstitute.dsde.firecloud.webservice

import java.text.SimpleDateFormat

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.service._

import scala.concurrent.ExecutionContext
import spray.json.{JsObject, JsString}
import spray.json.DefaultJsonProtocol._

object BuildTimeVersion {
  val version = Option(getClass.getPackage.getImplementationVersion)
  val versionJson = JsObject(Map("version" -> JsString(version.getOrElse("n/a"))))
}

trait StatusApiService extends Directives with RequestBuilding with SprayJsonSupport {

  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  implicit val executionContext: ExecutionContext

  val statusServiceConstructor: () => StatusService

  val statusRoutes: Route = {
    path("status") {
      get {
        complete { statusServiceConstructor().collectStatusInfo() }
      }
    } ~
      path( "version") {
        get { requestContext =>
          requestContext.complete(StatusCodes.OK, BuildTimeVersion.versionJson)
        }
      }
  }

}
