package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.core.ProfileActor.UpdateProfile
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{UserService, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import spray.client.pipelining._
import spray.http.HttpResponse
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing.RequestContext

import scala.collection.immutable.Iterable
import scala.concurrent.Future
import scala.util.{Failure, Success}

object ProfileActor {
  case class UpdateProfile(userInfo: UserInfo, profile: Profile)
  def props(requestContext: RequestContext): Props = Props(new ProfileActor(requestContext))
}

class ProfileActor(requestContext: RequestContext) extends Actor with FireCloudRequestBuilding {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  override def receive: Receive = {

    case UpdateProfile(userInfo: UserInfo, profile: Profile) =>
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val profilePropertyMap = profile.propertyValueMap
      val posts: Iterable[Future[HttpResponse]] = profilePropertyMap map {
        case (key, value) =>
          val kv = FireCloudKeyValue(Some(key), Some(value))
          pipeline {
            Post(UserService.remoteSetKeyURL, ThurloeKeyValue(Some(userInfo.getUniqueId), Some(kv)))
          }
      }
      val propertyUpdates: Future[List[HttpResponse]] = Future sequence posts.toList

      val kv2 = FireCloudKeyValue(Some("isRegistrationComplete"), Some("true"))
      val completionUpdate = pipeline {
        Post(UserService.remoteSetKeyURL, ThurloeKeyValue(Some(userInfo.getUniqueId), Some(kv2)))
      }

      propertyUpdates onComplete {
        case Success(responses) if responses.forall { _.status == OK } =>
          log.debug("Profile is saved, update isRegistrationComplete status.")
          completionUpdate onComplete {
            case Success(response) if response.status == OK =>
              context.parent ! RequestComplete(OK, response.entity)
              context stop self
            case Success(response) =>
              context.parent ! RequestCompleteWithErrorReport(response.status, response.toString)
              context stop self
            case _ =>
              context.parent ! RequestCompleteWithErrorReport(InternalServerError,
                "Failed to save registration completion status: [%s]".format(profilePropertyMap))
              context stop self
          }

        case Success(responses) =>
          val errors = responses.filterNot(_.status == OK) map { e => (e, ErrorReport.tryUnmarshal(e) ) }
          val errorReports = errors collect { case (_, Success(report)) => report }
          val missingReports = errors collect { case (originalError, Failure(_)) => originalError }
          val errorMessage = {
            val baseMessage = "%d failures out of %d attempts saving profile.  Errors: %s".format(profilePropertyMap.size, errors.size, errors mkString ",")
            if (missingReports.isEmpty) baseMessage
            else {
              val supplementalErrorMessage = "Additionally, %d of these failures did not provide error reports: %s".format(missingReports.size, missingReports mkString ",")
              baseMessage + "\n" + supplementalErrorMessage
            }
          }
          context.parent ! RequestCompleteWithErrorReport(InternalServerError, errorMessage, errorReports)
          context stop self
        case _ =>
          context.parent ! RequestCompleteWithErrorReport(InternalServerError, "Failure saving profile: [%s]".format(profilePropertyMap))
          context stop self
      }

  }

}
