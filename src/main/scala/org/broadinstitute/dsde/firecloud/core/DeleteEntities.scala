package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.contrib.pattern.Aggregator
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.core.DeleteEntities.ProcessUrl
import org.broadinstitute.dsde.firecloud.model.{EntityId, RequestCompleteWithErrorReport, ErrorReport}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
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

class DeleteEntitiesActor(requestContext: RequestContext, entities: Seq[EntityId]) extends Actor with Aggregator with FireCloudRequestBuilding {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  expectOnce {
    case ProcessUrl(baseUrl: String) =>
      log.debug(s"Deleting ${entities.size} entities for url: " + baseUrl)
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val deleteUrls = entities.map(e => FireCloudDirectiveUtils.encodeUri(s"$baseUrl/${e.entityType}/${e.entityName}"))
      val deleteFutures: Seq[Future[HttpResponse]] = deleteUrls.map(url => pipeline { Delete(url) })
      Future sequence deleteFutures onComplete {
        case Success(responses) if responses.forall(_.status.isSuccess) =>
          context.parent ! RequestComplete(OK)
          context stop self
        case Success(responses) =>
          val errors = responses.filterNot(_.status.isSuccess) map { e => (e, ErrorReport.tryUnmarshal(e)) }
          val errorReports = errors collect { case (_, Success(report)) => report }
          val missingReports = errors collect { case (originalError, Failure(_)) => originalError }

          val errorMessage = {
            val baseMessage = "%d failures out of %d attempts deleting entities.  Errors: %s".format(errors.size, entities.size, errors mkString ",")
            if (missingReports.isEmpty) baseMessage
            else {
              val supplementalErrorMessage = "Additionally, %d of these failures did not provide error reports: %s".format(missingReports.size, missingReports mkString ",")
              baseMessage + "\n" + supplementalErrorMessage
            }
          }

          context.parent ! RequestCompleteWithErrorReport(InternalServerError, errorMessage, errorReports)
          context stop self
        case _ =>
          context.parent ! RequestCompleteWithErrorReport(InternalServerError, "Failure deleting entities: [%s]".format(deleteUrls mkString ","))
          context stop self
      }
  }
}
