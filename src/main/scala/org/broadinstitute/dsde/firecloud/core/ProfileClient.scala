package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe

import org.broadinstitute.dsde.firecloud.core.ProfileClient.UpdateProfile
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{UserService, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}

import spray.client.pipelining._
import spray.http.{HttpRequest, HttpResponse}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object ProfileClient {
  case class UpdateProfile(userInfo: UserInfo, profile: Profile)
  def props(requestContext: RequestContext): Props = Props(new ProfileClientActor(requestContext))
}

class ProfileClientActor(requestContext: RequestContext) extends Actor with FireCloudRequestBuilding {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  override def receive: Receive = {

    case UpdateProfile(userInfo: UserInfo, profile: Profile) =>
      val parent = context.parent
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val profilePropertyMap = profile.propertyValueMap
      val propertyUpdates = updateUserProperties(pipeline, userInfo, profilePropertyMap)
      val profileResponse: Future[PerRequestMessage] = propertyUpdates flatMap { responses =>
        val allSucceeded = responses.forall { _.status.isSuccess }
        allSucceeded match {
          case true =>
            val kv2 = FireCloudKeyValue(Some("isRegistrationComplete"), Some("true"))
            val completionUpdate = pipeline {
              Post(UserService.remoteSetKeyURL, ThurloeKeyValue(Some(userInfo.getUniqueId), Some(kv2)))
            }
            completionUpdate.flatMap { response =>
              response.status match {
                case x if x.isSuccess => checkUserInRawls(pipeline, requestContext)
                case _ => Future(RequestCompleteWithErrorReport(response.status, response.toString))
              }
            } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, e.getMessage) }
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

      profileResponse pipeTo context.parent

  }

  def updateUserProperties(
    pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    userInfo: UserInfo,
    profilePropertyMap: Map[String, String]): Future[List[HttpResponse]] = {
    val propertyPosts = profilePropertyMap map {
      case (key, value) =>
        pipeline {
          Post(UserService.remoteSetKeyURL,
            ThurloeKeyValue(
              Some(userInfo.getUniqueId),
              Some(FireCloudKeyValue(Some(key), Some(value)))
          ))
        }
    }
    Future sequence propertyPosts.toList
  }

  def checkUserInRawls(
    pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    requestContext: RequestContext): Future[PerRequestMessage] = {
    pipeline { Get(UserService.rawlsRegisterUserURL) } flatMap { response =>
        response.status match {
          case x if x == OK =>
            Future(RequestComplete(OK))
          case x if x == NotFound =>
            registerUserInRawls(pipeline, requestContext)
          case _ =>
            Future(RequestCompleteWithErrorReport(response.status, response.toString))
        }
    } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, e.getMessage) }
  }

  def registerUserInRawls(
    pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    requestContext: RequestContext): Future[PerRequestMessage] = {
    pipeline { Post(UserService.rawlsRegisterUserURL) } map { response =>
        response.status match {
          case x if x.isSuccess || isConflict(response) =>
            RequestComplete(OK)
          case _ =>
            RequestCompleteWithErrorReport(response.status, response.toString)
        }
    } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, e.getMessage) }
  }

  /**
    * Rawls can come back with a conflict (500 wrapping a 409) if the user exists either in
    * Rawls or LDAP when registering. That is not a real error so we can consider that a success.
    *
    * @param postResponse The HttpResponse
    * @return A conflict or not
    */
  def isConflict(postResponse: HttpResponse): Boolean = {
    (postResponse.status == InternalServerError || postResponse.status == Conflict) &&
      postResponse.entity.asString.contains(Conflict.intValue.toString) &&
      postResponse.entity.asString.contains(Conflict.reason)
  }

}
