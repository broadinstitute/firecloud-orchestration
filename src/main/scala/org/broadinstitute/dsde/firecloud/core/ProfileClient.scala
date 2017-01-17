package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, ActorRefFactory, Props}
import akka.event.Logging
import akka.pattern.pipe
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.ProfileClient._
import org.broadinstitute.dsde.firecloud.dataaccess.{HttpGoogleServicesDAO, HttpRawlsDAO, HttpThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.{FireCloudRequestBuilding, UserService}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http.{HttpRequest, HttpResponse, StatusCodes, Uri}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success, Try}

object ProfileClient {
  case class UpdateProfile(userInfo: UserInfo, profile: BasicProfile)
  case class UpdateNIHLinkAndSyncSelf(userInfo: UserInfo, nihLink: NIHLink)
  case object SyncWhitelist

  def props(requestContext: RequestContext): Props = Props(new ProfileClientActor(requestContext))
}

class ProfileClientActor(requestContext: RequestContext) extends Actor with FireCloudRequestBuilding {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  override def receive: Receive = {

    case UpdateProfile(userInfo: UserInfo, profile: BasicProfile) =>
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val profilePropertyMap = profile.propertyValueMap ++ Map("email" -> userInfo.userEmail)
      val propertyUpdates = updateUserProperties(pipeline, userInfo, profilePropertyMap)
      val profileResponse: Future[PerRequestMessage] = propertyUpdates flatMap { responses =>
        val allSucceeded = responses.forall { _.status.isSuccess }
        allSucceeded match {
          case true =>
            val kv2 = FireCloudKeyValue(Some("isRegistrationComplete"), Some(Profile.currentVersion.toString))
            val completionUpdate = pipeline {
              Post(UserService.remoteSetKeyURL, ThurloeKeyValue(Some(userInfo.getUniqueId), Some(kv2)))
            }
            completionUpdate.flatMap { response =>
              response.status match {
                case x if x.isSuccess => checkUserInRawls(pipeline, requestContext, userInfo.getUniqueId)
                case _ => Future(RequestCompleteWithErrorReport(response.status,
                                  "Profile partially saved, but error completing profile", Seq(ErrorReport(response))))
              }
            } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError,
                                              "Profile partially saved, but unexpected error completing profile", e) }
          case false => handleFailedUpdateResponse(responses, profilePropertyMap)
        }
      } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError,
                                        "Unexpected error saving profile", e) }

      profileResponse pipeTo sender

    case UpdateNIHLinkAndSyncSelf(userInfo: UserInfo, nihLink: NIHLink) =>
      val syncWhiteListResult = syncWhitelist(Some(userInfo.getUniqueId))
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

      // Complete syncWhitelist and ignore as neither success nor failure are useful to the client
      syncWhiteListResult map(Success(_)) recover { case t => Failure(t) } flatMap { _ =>
        profileResponse
      } pipeTo sender

    case SyncWhitelist =>
      syncWhitelist() pipeTo sender
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
    requestContext: RequestContext, userId: String): Future[PerRequestMessage] = {
    pipeline { Get(UserService.rawlsRegisterUserURL) } flatMap { response =>
        response.status match {
          case x if x == OK =>
            Future(RequestComplete(OK))
          case x if x == NotFound =>
            registerUserInRawls(pipeline, requestContext, userId)
          case _ =>
            Future(RequestCompleteWithErrorReport(response.status,
                    "Profile saved, but error verifying user registration", Seq(ErrorReport(response))))
        }
    } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError,
                                      "Profile saved, but unexpected error verifying user registration", e) }
  }

  def registerUserInRawls(
    pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    requestContext: RequestContext, userId: String): Future[PerRequestMessage] = {
    pipeline { Post(UserService.rawlsRegisterUserURL) } map { response =>
        response.status match {
          case x if x.isSuccess || isConflict(response) =>
            //sendNotification is purposely detached from the sequence. we don't want to block
            //the completion of registration by waiting for sendgrid to succeed or fail the registration
            // if sendgrid fails. unfortunately, if this call fails then ¯\_(ツ)_/¯
            sendNotification(pipeline, ActivationNotification(userId))
            RequestComplete(OK)
          case _ =>
            RequestCompleteWithErrorReport(response.status,
              "Profile saved, but error registering user", Seq(ErrorReport(response)))
        }
    } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError,
                                      "Profile saved, but unexpected error registering user", e) }
  }

  // GAWB-1314
  def sendNotification(pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    notification: Notification) = {
    pipeline {
      Post(UserService.remotePostNotifyURL, List(ThurloeNotification(notification.userId, notification.replyTo, notification.notificationId, notification.toMap)))
    } flatMap { response =>
      if(response.status.isFailure)
        log.warning(s"Could not send notification: ${notification}")
      Future.successful(response)
    }
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

  def syncWhitelist(subjectId: Option[String] = None): Future[PerRequestMessage] = Try {
    val (bucket, file) = (FireCloudConfig.Nih.whitelistBucket, FireCloudConfig.Nih.whitelistFile)
    val whitelist = Source.fromInputStream(HttpGoogleServicesDAO.getBucketObjectAsInputStream(bucket, file)).getLines().toSet

    // note: everything after this point involves Futures

    /* If we're syncing the entire whitelist, we want to make sure that those users don't have expired access,
        so we need to check the expiration dates for every user.
       If we're syncing just an individual user, we don't want to check their expiration date because:
        a) this may be their first time linking, so they have no expiration date set
        b) they may be re-linking to update their expired access
     */
    val memberList = subjectId match {
      case None => {
        val nihEnabledFireCloudUsers = NIHWhitelistUtils.getCurrentNihUsernameMap() map { mapping =>
          mapping.collect { case (fcUser, nihUser) if whitelist contains nihUser => fcUser }.toSeq
        }

        nihEnabledFireCloudUsers map { subjectIds => {
          RawlsGroupMemberList(userSubjectIds = Some(subjectIds))
        }}
      }
      case Some(id) => Future.successful(RawlsGroupMemberList(userSubjectIds = Some(Seq(id))))
    }

    val pipeline = addAdminCredentials ~> sendReceive
    val url = FireCloudConfig.Rawls.overwriteGroupMembershipUrlFromGroupName(FireCloudConfig.Nih.rawlsGroupName)

    // if we have a subjectId specified, we are activating a single user at runtime.
    // Use the Post endpoint, which is add-only.
    //
    // if we do NOT have a subjectId, we are syncing the entire whitelist.
    // Use the Put endpoint, which replaces all members.
    val rawlsRequestMethod = subjectId match {
      case Some(user) => Post
      case None => Put
    }

    memberList flatMap { members => pipeline { rawlsRequestMethod(url, members) } } map { response =>
      if(response.status.isFailure)
        RequestCompleteWithErrorReport(InternalServerError, "Error synchronizing NIH whitelist")
      else RequestComplete(NoContent) // don't leak any sensitive data
    }
  }.get recover {
    // intentionally quash errors so as not to leak sensitive data
    case _ => RequestCompleteWithErrorReport(InternalServerError, "Error synchronizing NIH whitelist")
  }

}

object NIHWhitelistUtils extends FireCloudRequestBuilding {
  def getAllUserValuesForKey(key: String, userId: Option[String] = None)(implicit arf: ActorRefFactory, ec: ExecutionContext): Future[Map[String, String]] = {
    val pipeline = addAdminCredentials ~> addFireCloudCredentials ~> sendReceive

    val queryParams = userId match {
      case Some(sub) => Map("key"->key, "userId"->sub)
      case None      => Map("key"->key)
    }
    val queryUri = Uri(UserService.remoteGetQueryURL).withQuery(queryParams)

    pipeline {
      Get(queryUri)
    } map { response =>
      if (response.status != OK) throw new Exception(response.toString)   // TODO: better error handling
      response.entity.as[Seq[ThurloeKeyValue]] match {
        case Right(seq) =>
          val resultOptions = seq map { tkv => (tkv.userId, tkv.keyValuePair.flatMap { kvp => kvp.value }) }
          val actualResultsOnly = resultOptions collect { case (Some(firecloudSubjId), Some(thurloeValue)) => (firecloudSubjId, thurloeValue) }
          actualResultsOnly.toMap
        case Left(err) => throw new Exception(err.toString)   // TODO: better error handling
      }
    }
  }

  def filterForCurrentUsers(nihUsernames: Future[Map[String, String]], nihExpiretimes: Future[Map[String, String]])(implicit ec: ExecutionContext): Future[Map[String, String]] = {
    for {
      usernames <- nihUsernames
      expirations <- nihExpiretimes
    } yield {
      val currentFcUsers = expirations.map {
        case (fcUser, expStr: String) => fcUser -> Try { expStr.toLong }.toOption
      }.collect {
        case (fcUser, Some(exp: Long)) if DateUtils.now < exp => fcUser
      }.toSet

      usernames.filter { case (fcUser, nihUser) => currentFcUsers.contains(fcUser) }
    }
  }

  // get a mapping of FireCloud user name to NIH User name, for only those Thurloe users with a non-expired NIH link
  def getCurrentNihUsernameMap()(implicit arf: ActorRefFactory, ec: ExecutionContext): Future[Map[String, String]] = {
    val nihUsernames = getAllUserValuesForKey("linkedNihUsername")
    val nihExpiretimes = getAllUserValuesForKey("linkExpireTime")

    filterForCurrentUsers(nihUsernames, nihExpiretimes)
  }

}
