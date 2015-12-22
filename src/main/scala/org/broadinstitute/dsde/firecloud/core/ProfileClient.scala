package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe
import com.ocpsoft.pretty.time.PrettyTime

import org.broadinstitute.dsde.firecloud.core.ProfileClient.{GetNIHStatus, UpdateNIHLink, UpdateProfile}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{UserService, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.joda.time.{Hours, DateTime}

import spray.client.pipelining._
import spray.http.{StatusCodes, HttpRequest, HttpResponse}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.routing.RequestContext


import scala.concurrent.Future
import scala.util.{Failure, Success}

object ProfileClient {
  case class UpdateProfile(userInfo: UserInfo, profile: Profile)
  case class UpdateNIHLink(userInfo: UserInfo, nihLink: NIHLink)
  case class GetNIHStatus(userInfo: UserInfo)
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
                case _ => Future(RequestCompleteWithErrorReport(response.status,
                                  "Profile partially saved, but error completing profile", Seq(ErrorReport(response))))
              }
            } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError,
                                              "Profile partially saved, but unexpected error completing profile", e) }
          case false => handleFailedUpdateResponse(responses, profilePropertyMap)
        }
      } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError,
                                        "Unexpected error saving profile", e) }

      profileResponse pipeTo context.parent

    case UpdateNIHLink(userInfo: UserInfo, nihLink: NIHLink) =>
      val parent = context.parent
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val profilePropertyMap = nihLink.propertyValueMap
      val propertyUpdates = updateUserProperties(pipeline, userInfo, profilePropertyMap)
      val profileResponse: Future[PerRequestMessage] = propertyUpdates flatMap { responses =>
        val allSucceeded = responses.forall { _.status.isSuccess }
        allSucceeded match {
          case true => Future(RequestComplete(OK))
          case false => handleFailedUpdateResponse(responses, profilePropertyMap)
        }
      } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError,
        "Unexpected error updating NIH link", e) }

      profileResponse pipeTo context.parent

    case GetNIHStatus(userInfo: UserInfo) =>
      val parent = context.parent
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val profileReq = Get(UserService.remoteGetAllURL.format(userInfo.getUniqueId))

      val profileResponse: Future[PerRequestMessage] = pipeline(profileReq) map {response:HttpResponse =>
        response.status match {
          case OK =>
            val profileEither = response.entity.as[ProfileWrapper]
            profileEither match {
              case Right(profileWrapper) =>
                val profile = Profile(profileWrapper)
                profile.linkedNihUsername match {
                  case Some(nihUsername) =>
                    // we have a linked profile.
                    profile.isDbgapAuthorized match {
                      // TODO: if the user is not dbGaP authorized, should we bother making them log in to NIH?
                        // change the below from "Some(x)" to "Some(true)" to only consider dbGaP-authorized users
                      // TODO: isDbgapAuthorized should really defer to rawls or wherever else has the source-of-record
                      // TODO: we don't really need to check both lastLinkTime and linkExpireTime here.
                        // but, until the main OAuth login sets linkExpireTime, we need it for safety.
                      case Some(x) =>
                        (profile.lastLinkTime, profile.linkExpireTime) match {
                          case (Some(lastLinkSeconds:Long), Some(linkExpireSeconds:Long)) =>
                            val howOld = DateUtils.hoursSince(lastLinkSeconds)
                            val howSoonExpire = DateUtils.secondsSince(linkExpireSeconds)

                            val loginRequired = (howOld >= 24 || howSoonExpire >= 0)

                            val secsSinceLastLink = DateUtils.secondsSince(lastLinkSeconds)

                            val descSince = DateUtils.prettySince(lastLinkSeconds)

                            val statusResponse = NIHStatus(
                              loginRequired,
                              linkedNihUsername = profile.linkedNihUsername,
                              isDbgapAuthorized = profile.isDbgapAuthorized,
                              lastLinkTime = Some(lastLinkSeconds),
                              linkExpireTime = profile.linkExpireTime,
                              secondsSinceLastLink = Some(secsSinceLastLink),
                              descriptionSinceLastLink = Some(descSince))

                            RequestComplete(StatusCodes.OK, statusResponse)
                          case _ =>
                            // user is linked and authorized, but we have no record of login time or expiration time.
                            RequestComplete(StatusCodes.OK, NIHStatus(true,
                              linkedNihUsername = profile.linkedNihUsername,
                              lastLinkTime = profile.lastLinkTime,
                              linkExpireTime = profile.linkExpireTime,
                              isDbgapAuthorized = profile.isDbgapAuthorized))

                        }
                      case _ => RequestComplete(NoContent, "Not dbGaP authorized")
                    }
                  case _ =>
                    RequestComplete(NotFound, "Linked NIH username not found")
                }
              case Left(err) => RequestCompleteWithErrorReport(InternalServerError,
                "Could not unmarshal profile response: " + err.toString, Seq())
            }
          case x =>
            // request for profile returned non-200
            RequestCompleteWithErrorReport(x, response.toString, Seq())
        }
      } recover {
        // unexpected error retrieving profile
        case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, e.getMessage)
      }

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

  def handleFailedUpdateResponse(
    responses:List[HttpResponse],
    profilePropertyMap:Map[String,String]) = {
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
            Future(RequestCompleteWithErrorReport(response.status,
                    "Profile saved, but error verifying user registration", Seq(ErrorReport(response))))
        }
    } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError,
                                      "Profile saved, but unexpected error verifying user registration", e) }
  }

  def registerUserInRawls(
    pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    requestContext: RequestContext): Future[PerRequestMessage] = {
    pipeline { Post(UserService.rawlsRegisterUserURL) } map { response =>
        response.status match {
          case x if x.isSuccess || isConflict(response) =>
            RequestComplete(OK)
          case _ =>
            RequestCompleteWithErrorReport(response.status,
              "Profile saved, but error registering user", Seq(ErrorReport(response)))
        }
    } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError,
                                      "Profile saved, but unexpected error registering user", e) }
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
