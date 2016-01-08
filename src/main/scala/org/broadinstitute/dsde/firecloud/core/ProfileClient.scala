package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe
import org.broadinstitute.dsde.firecloud.FireCloudConfig

import org.broadinstitute.dsde.firecloud.core.ProfileClient._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{UserService, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils

import spray.client.pipelining._
import spray.http.{StatusCode, StatusCodes, HttpRequest, HttpResponse}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object ProfileClient {
  case class UpdateProfile(userInfo: UserInfo, profile: BasicProfile)
  case class UpdateNIHLink(userInfo: UserInfo, nihLink: NIHLink)
  case class GetNIHStatus(userInfo: UserInfo)
  case class GetAndUpdateNIHStatus(userInfo: UserInfo)
  def props(requestContext: RequestContext): Props = Props(new ProfileClientActor(requestContext))
}

class ProfileClientActor(requestContext: RequestContext) extends Actor with FireCloudRequestBuilding {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  override def receive: Receive = {

    case UpdateProfile(userInfo: UserInfo, profile: BasicProfile) =>
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
      val profileResponse = getProfile(userInfo, false)
      profileResponse pipeTo context.parent

    case GetAndUpdateNIHStatus(userInfo: UserInfo) =>
      getProfile(userInfo, true)
      // do NOT pipe the response back to the parent!
  }

  def getProfile(userInfo: UserInfo, updateExpiration: Boolean): Future[PerRequestMessage] = {
    val pipeline = addCredentials(userInfo.accessToken) ~> sendReceive
    val profileReq = Get(UserService.remoteGetAllURL.format(userInfo.getUniqueId))

    pipeline(profileReq) flatMap { response: HttpResponse =>
      response.status match {
        case OK =>
          val profileEither = response.entity.as[ProfileWrapper]
          profileEither match {
            case Right(profileWrapper) =>
              val profile = Profile(profileWrapper)
              profile.linkedNihUsername match {
                case Some(nihUsername) =>
                  // we have a linked profile.

                  // if we have no record of the user logging in or the user's expire time, default to 0
                  val lastLinkSeconds = profile.lastLinkTime.getOrElse(0L)
                  var linkExpireSeconds = profile.linkExpireTime.getOrElse(0L)

                  val howOld = DateUtils.hoursSince(lastLinkSeconds)
                  val howSoonExpire = DateUtils.secondsSince(linkExpireSeconds)

                  // if the user's link has expired, the user must re-link.
                  // NB: we use a separate val here in case we need to change the logic. For instance, we could
                  // change the logic later to be "link has expired OR user hasn't logged in within 24 hours"
                  val loginRequired = (howSoonExpire >= 0)

                  // if the user has not logged in to FireCloud within 24 hours, AND the user's expiration is
                  // further out than 24 hours, reset the user's expiration.
                  if (updateExpiration && howOld >= 24 && Math.abs(howSoonExpire) >= (24*60*60)) {
                    // generate time now+24 hours
                    linkExpireSeconds = DateUtils.nowPlus24Hours
                    // update linkExpireTime in Thurloe for this user
                    val expireKVP = FireCloudKeyValue(Some("linkExpireTime"), Some(linkExpireSeconds.toString))
                    val expirePayload = ThurloeKeyValue(Some(userInfo.getUniqueId), Some(expireKVP))

                    val postPipeline = addCredentials(userInfo.accessToken) ~> sendReceive
                    val updateReq = Post(UserService.remoteSetKeyURL, expirePayload)

                    postPipeline(updateReq) map { response: HttpResponse =>
                      response.status match {
                        case OK => log.info(s"User with linked NIH account [%s] now has 24 hours to re-link.".format(nihUsername))
                        case x =>
                          log.warning(s"User with linked NIH account [%s] requires re-link within 24 hours, ".format(nihUsername) +
                            s"but the system encountered a failure updating link expiration: " + response.toString)
                      }
                    } recover {
                      case e: Throwable =>
                        // TODO: COULD NOT UPDATE link expire in Thurloe
                        log.warning(s"User with linked NIH account [%s] requires re-link within 24 hours, ".format(nihUsername) +
                          s"but the system encountered an unexpected error updating link expiration: " + e.getMessage, e)
                    }
                  }

                  val descriptionSince = DateUtils.prettySince(lastLinkSeconds)
                  val descriptionExpires = DateUtils.prettySince(linkExpireSeconds)

                  getNIHStatusResponse(pipeline, loginRequired, profile)

                case _ =>
                  Future(RequestComplete(NotFound, "Linked NIH username not found"))
              }
            case Left(err) =>
              Future(RequestCompleteWithErrorReport(InternalServerError,
                "Could not unmarshal profile response: " + err.toString, Seq()))
          }
        case x =>
          // request for profile returned non-200
          Future(RequestCompleteWithErrorReport(x, response.toString, Seq()))
      }
    } recover {
      // unexpected error retrieving profile
      case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, e.getMessage)
    }

  }

  def getNIHStatusResponse(pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    loginRequired: Boolean, profile: Profile): Future[PerRequestMessage] = {
    val dbGapUrl = UserService.groupUrl(FireCloudConfig.Rawls.dbGapAuthorizedUsersGroup)
    val isDbGapAuthorizedRequest = Get(dbGapUrl)

    pipeline(isDbGapAuthorizedRequest) map { response: HttpResponse =>
      val authorized: Boolean = response.status match {
        case x if x == OK => true
        case _ => false
      }
      RequestComplete(OK,
        NIHStatus(
          loginRequired,
          linkedNihUsername = profile.linkedNihUsername,
          isDbgapAuthorized = Some(authorized),
          lastLinkTime = Some(profile.lastLinkTime.getOrElse(0L)),
          linkExpireTime = Some(profile.linkExpireTime.getOrElse(0L)),
          descriptionSinceLastLink = Some(DateUtils.prettySince(profile.lastLinkTime.getOrElse(0L))),
          descriptionUntilExpires = Some(DateUtils.prettySince(profile.linkExpireTime.getOrElse(0L)))
        )
      )
    } recover {
      // unexpected error retrieving dbgap group status
      case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, e.getMessage)
    }
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
