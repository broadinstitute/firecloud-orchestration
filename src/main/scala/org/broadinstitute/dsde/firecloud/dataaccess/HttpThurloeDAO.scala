package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException}
import org.broadinstitute.dsde.firecloud.model.{Notification, Profile, ThurloeNotification, _}
import org.broadinstitute.dsde.firecloud.service.{FireCloudRequestBuilding, UserService}
import org.broadinstitute.dsde.firecloud.utils.{DateUtils, RestJsonClient}
import spray.http.{HttpHeaders, HttpRequest, HttpResponse, OAuth2BearerToken}
import spray.httpx.TransformerPipelineSupport.WithTransformerConcatenation
import spray.client.pipelining._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Created by mbemis on 10/21/16.
 */
class HttpThurloeDAO ( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends ThurloeDAO with FireCloudRequestBuilding with RestJsonClient {

  val adminToken = HttpGoogleServicesDAO.getAdminUserAccessToken

  override def sendNotifications(notifications: Seq[Notification]): Future[Try[Unit]] = {

    val notificationPipeline = addCredentials(OAuth2BearerToken(adminToken)) ~> addHeader(fireCloudHeader) ~> sendReceive
    val thurloeNotifications = notifications.map(n => ThurloeNotification(n.userId, n.replyTo, n.notificationId, n.toMap))

    notificationPipeline(Post(UserService.remotePostNotifyURL, thurloeNotifications)) map {
      case response if response.status.isSuccess => Success(())
      case _ => Failure(new FireCloudException(s"Unable to send notifications ${notifications}"))
    }
  }

  override def getProfile(userInfo: UserInfo, updateExpiration: Boolean): Future[PerRequestMessage] = {
    val pipeline = addFireCloudCredentials ~> addCredentials(userInfo.accessToken) ~> sendReceive
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

                  // get the current link expiration time
                  val profileExpiration = profile.linkExpireTime.getOrElse(0L)

                  // calculate the possible new link expiration time
                  val linkExpireSeconds = if (updateExpiration) {
                    calculateExpireTime(profile)
                  } else {
                    profileExpiration
                  }

                  // if the link expiration time needs updating (i.e. is different than what's in the profile), do the updating
                  if (linkExpireSeconds != profileExpiration) {
                    val expireKVP = FireCloudKeyValue(Some("linkExpireTime"), Some(linkExpireSeconds.toString))
                    val expirePayload = ThurloeKeyValue(Some(userInfo.getUniqueId), Some(expireKVP))

                    val postPipeline = addFireCloudCredentials ~> addCredentials(userInfo.accessToken) ~> sendReceive
                    val updateReq = Post(UserService.remoteSetKeyURL, expirePayload)

                    postPipeline(updateReq) map { response: HttpResponse =>
                      response.status match {
                        case OK => logger.info(s"User with linked NIH account [%s] now has 24 hours to re-link.".format(nihUsername))
                        case x =>
                          logger.warn(s"User with linked NIH account [%s] requires re-link within 24 hours, ".format(nihUsername) +
                            s"but the system encountered a failure updating link expiration: " + response.toString)
                      }
                    } recover {
                      case e: Throwable =>
                        // TODO: COULD NOT UPDATE link expire in Thurloe
                        logger.warn(s"User with linked NIH account [%s] requires re-link within 24 hours, ".format(nihUsername) +
                          s"but the system encountered an unexpected error updating link expiration: " + e.getMessage, e)
                    }
                  }

                  val howSoonExpire = DateUtils.secondsSince(linkExpireSeconds)

                  // if the user's link has expired, the user must re-link.
                  // NB: we use a separate val here in case we need to change the logic. For instance, we could
                  // change the logic later to be "link has expired OR user hasn't logged in within 24 hours"
                  val loginRequired = (howSoonExpire >= 0)

                  getNIHStatusResponse(pipeline, loginRequired, profile, linkExpireSeconds)

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

  private def getNIHStatusResponse(pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    loginRequired: Boolean, profile: Profile, linkExpireSeconds: Long): Future[PerRequestMessage] = {
    val dbGapUrl = UserService.groupUrl(FireCloudConfig.Nih.rawlsGroupName)
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
          linkExpireTime = Some(linkExpireSeconds),
          descriptionSinceLastLink = Some(DateUtils.prettySince(profile.lastLinkTime.getOrElse(0L))),
          descriptionUntilExpires = Some(DateUtils.prettySince(linkExpireSeconds))
        )
      )
    } recover {
      // unexpected error retrieving dbgap group status
      case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, e.getMessage)
    }
  }

  private def calculateExpireTime(profile:Profile): Long = {
    (profile.lastLinkTime, profile.linkExpireTime) match {
      case (Some(lastLink), Some(expire)) if (lastLink < DateUtils.nowMinus24Hours && expire > DateUtils.nowPlus24Hours) =>
        // The user has not logged in to FireCloud within 24 hours, AND the user's expiration is
        // more than 24 hours in the future. Reset the user's expiration.
        DateUtils.nowPlus24Hours
      case (Some(lastLink), Some(expire)) =>
        // User in good standing; return the expire time unchanged
        expire
      case _ =>
        // Either last-link or expire is missing. Reset the user's expiration.
        DateUtils.nowPlus24Hours
    }
  }
}
