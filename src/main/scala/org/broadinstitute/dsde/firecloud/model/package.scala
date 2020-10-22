package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedRequestContentRejection, MethodRejection, RejectionHandler}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}

import scala.language.implicitConversions

package object model {
  implicit val errorReportSource = ErrorReportSource("FireCloud")

  import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
  import spray.json._

}
