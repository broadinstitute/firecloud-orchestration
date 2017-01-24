package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{Notification, Profile, ThurloeNotification, _}
import org.broadinstitute.dsde.firecloud.service.{FireCloudRequestBuilding, UserService}
import org.broadinstitute.dsde.firecloud.utils.{DateUtils, RestJsonClient}
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http.{HttpResponse, OAuth2BearerToken, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Created by mbemis on 10/21/16.
 */
class HttpThurloeDAO ( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends ThurloeDAO with FireCloudRequestBuilding with RestJsonClient {

  def adminToken = HttpGoogleServicesDAO.getAdminUserAccessToken

  override def sendNotifications(notifications: Seq[Notification]): Future[Try[Unit]] = {

    val notificationPipeline = addCredentials(OAuth2BearerToken(adminToken)) ~> addHeader(fireCloudHeader) ~> sendReceive
    val thurloeNotifications = notifications.map(n => ThurloeNotification(n.userId, n.userEmail, n.replyTo, n.notificationId, n.toMap))

    notificationPipeline(Post(UserService.remotePostNotifyURL, thurloeNotifications)) map {
      case response if response.status.isSuccess => Success(())
      case _ => Failure(new FireCloudException(s"Unable to send notifications ${notifications}"))
    }
  }

  override def getProfile(userInfo: UserInfo): Future[Option[Profile]] = {
    val pipeline = addFireCloudCredentials ~> addCredentials(userInfo.accessToken) ~> sendReceive
    pipeline(Get(UserService.remoteGetAllURL.format(userInfo.getUniqueId))) map { response =>
      response.status match {
        case StatusCodes.OK => Some(Profile(unmarshal[ProfileWrapper].apply(response)))
        case StatusCodes.NotFound => None
        case _ => throwBadResponse(response)
      }
    }
  }

  override def saveProfile(userInfo: UserInfo, profile: BasicProfile): Future[Int] = {
    val pipeline = addFireCloudCredentials ~> addCredentials(userInfo.accessToken) ~> sendReceive
    val profilePropertyMap: Map[String, String] = profile.propertyValueMap ++ Map("email" -> userInfo.userEmail)
    profilePropertyMap map {
      case (key, value) =>
        pipeline {
          Post(UserService.remoteSetKeyURL,
            ThurloeKeyValue(
              Some(userInfo.getUniqueId),
              Some(FireCloudKeyValue(Some(key), Some(value)))
            ))
        } map { response =>
          response.status match {
            case StatusCodes.OK => 1
            case _ => 0
          }
        }
    }.sum
//    propertyPosts map { a => a.
//
//    }
//    map { response =>
//      response.status match {
//        case StatusCodes.OK => 1
//        case _ => 0
//      }
//    } reduce { (total, next) => total + next }
  }

  override def maybeUpdateNihLinkExpiration(userInfo: UserInfo, profile: Profile): Future[Unit] = {
    profile.linkedNihUsername match {
      case Some(nihUsername) =>
        val profileExpiration = profile.linkExpireTime.getOrElse(0L)
        val linkExpireSeconds = HttpThurloeDAO.calculateExpireTime(profile)
        if (linkExpireSeconds != profileExpiration) {
          val expireKVP = FireCloudKeyValue(Some("linkExpireTime"), Some(linkExpireSeconds.toString))
          val expirePayload = ThurloeKeyValue(Some(userInfo.getUniqueId), Some(expireKVP))
          val updateReq = Post(UserService.remoteSetKeyURL, expirePayload)
          val pipeline = addFireCloudCredentials ~> addCredentials(userInfo.accessToken) ~>
            sendReceive
          pipeline(updateReq) map { _ => () }
        } else
          Future.successful(Unit)
      case None => Future.successful(Unit)
    }
  }

  private def throwBadResponse(response: HttpResponse) = {
    throw new FireCloudExceptionWithErrorReport(ErrorReport("Thurloe", response))
  }
}

object HttpThurloeDAO {
  // Not private to allow this to be called by tests.
  def calculateExpireTime(profile:Profile): Long = {
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
