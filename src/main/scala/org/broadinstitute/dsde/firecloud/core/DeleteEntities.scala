package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe
import org.broadinstitute.dsde.firecloud.core.DeleteEntities.ProcessUrl
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.firecloud.model.{EntityId, RequestCompleteWithErrorReport}
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectiveUtils, FireCloudRequestBuilding}
import spray.client.pipelining._
import spray.http.HttpResponse
import spray.http.StatusCodes._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object DeleteEntities {
  case class ProcessUrl(url: String)
  def props(requestContext: RequestContext, entities: Seq[EntityId]): Props =
    Props(new DeleteEntitiesActor(requestContext, entities))
}

class DeleteEntitiesActor(requestContext: RequestContext, entities: Seq[EntityId]) extends Actor with FireCloudRequestBuilding {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  def receive = {
    case ProcessUrl(baseUrl: String) =>
      log.debug(s"Deleting ${entities.size} entities for url: " + baseUrl)
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val deleteUrls = entities.map(e => FireCloudDirectiveUtils.encodeUri(s"$baseUrl/${e.entityType}/${e.entityName}"))
      val deleteFutures: Seq[Future[HttpResponse]] = deleteUrls.map(url => pipeline { Delete(url) })
      createDeleteResponse(Future sequence deleteFutures) pipeTo context.parent
  }

  def createDeleteResponse(deleteFutures: Future[Seq[HttpResponse]]): Future[PerRequestMessage] = {
    deleteFutures.map
      { responses =>
        val allSucceeded = responses.forall(_.status.isSuccess)
        allSucceeded match {
          case true =>
            RequestComplete(OK)
          case false =>
            val errors = responses.filterNot(_.status.isSuccess) map { e => (e, FCErrorReport.tryUnmarshal(e)) }
            val errorReports = errors collect { case (_, Success(report)) => report }
            val missingReports = errors collect { case (originalError, Failure(_)) => originalError }
            val errorMessage = {
              val baseMessage = "%d entities deleted, with %d failures.  Errors: %s"
                .format(entities.size, errors.size, errors mkString ",")
              if (missingReports.isEmpty) baseMessage
              else {
                val supplementalErrorMessage = "Additionally, %d of these failures did not provide error reports: %s"
                  .format(missingReports.size, missingReports mkString ",")
                baseMessage + "\n" + supplementalErrorMessage
              }
            }
            RequestCompleteWithErrorReport(InternalServerError, errorMessage, errorReports)
        }
      }.recoverWith {
      case e: Throwable => Future(RequestCompleteWithErrorReport(InternalServerError, e.getMessage))
    }
  }
}
