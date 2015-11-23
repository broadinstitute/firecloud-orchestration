package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe

import org.broadinstitute.dsde.firecloud.core.ProfileActor.UpdateProfile
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{UserService, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}

import spray.client.pipelining._
import spray.http.{StatusCode, HttpResponse}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing.RequestContext

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
      val parent = context.parent
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val profilePropertyMap = profile.propertyValueMap
      val propertyPosts = profilePropertyMap map {
        case (key, value) =>
          val kv = FireCloudKeyValue(Some(key), Some(value))
          pipeline {
            Post(UserService.remoteSetKeyURL, ThurloeKeyValue(Some(userInfo.getUniqueId), Some(kv)))
          }
      }
      val propertyUpdates: Future[List[HttpResponse]] = Future sequence propertyPosts.toList
      val updatedPropertyResponse: Future[PerRequestMessage] = propertyUpdates flatMap { responses =>
        val allSucceeded = responses.forall { _.status == OK }
        allSucceeded match {
          case true =>
            val kv2 = FireCloudKeyValue(Some("isRegistrationComplete"), Some("true"))
            val completionUpdate = pipeline {
              Post(UserService.remoteSetKeyURL, ThurloeKeyValue(Some(userInfo.getUniqueId), Some(kv2)))
            }
            completionUpdate.map { response =>
              response.status match {
                case OK => RequestComplete(OK, response.entity)
                case _ => RequestCompleteWithErrorReport(response.status, response.toString)
              }
            } recoverWith { case e: Throwable => Future(RequestCompleteWithErrorReport(InternalServerError, e.getMessage)) }
          case false =>
            val errors = responses.filterNot(_.status == OK) map { e => (e, ErrorReport.tryUnmarshal(e) ) }
            val errorReports = errors collect { case (_, Success(report)) => report }
            val missingReports = errors collect { case (originalError, Failure(_)) => originalError }
            val errorMessage = {
              val baseMessage = "%d failures out of %d attempts saving profile.  Errors: %s"
                .format(profilePropertyMap.size, errors.size, errors mkString ",")
              if (missingReports.isEmpty) baseMessage
              else {
                val supplementalErrorMessage = "Additionally, %d of these failures did not provide error reports: %s"
                  .format(missingReports.size, missingReports mkString ",")
                baseMessage + "\n" + supplementalErrorMessage
              }
            }
            Future(RequestCompleteWithErrorReport(InternalServerError, errorMessage, errorReports))
        }
      } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, e.getMessage) }

      updatedPropertyResponse pipeTo context.parent

  }

}
