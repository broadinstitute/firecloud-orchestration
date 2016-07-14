package org.broadinstitute.dsde.firecloud.core

import akka.actor.{ActorRefFactory, Actor, Props}
import akka.event.Logging
import akka.pattern.pipe
import org.broadinstitute.dsde.firecloud.FireCloudConfig

import org.broadinstitute.dsde.firecloud.core.ProfileClient._
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{UserService, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils

import spray.client.pipelining._
import spray.http.{Uri, HttpRequest, HttpResponse}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Try, Failure, Success}

object ProfileClient {
  case class UpdateProfile(userInfo: UserInfo, profile: BasicProfile)
  case class UpdateNIHLinkAndSyncSelf(userInfo: UserInfo, nihLink: NIHLink)
  case class GetNIHStatus(userInfo: UserInfo)
  case class GetAndUpdateNIHStatus(userInfo: UserInfo)
  case object SyncWhitelist

  def props(requestContext: RequestContext): Props = Props(new ProfileClientActor(requestContext))

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

class ProfileClientActor(requestContext: RequestContext) extends Actor with FireCloudRequestBuilding {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  override def receive: Receive = {

    case UpdateProfile(userInfo: UserInfo, profile: BasicProfile) =>
      val parent = context.parent
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

      profileResponse pipeTo context.parent

    case UpdateNIHLinkAndSyncSelf(userInfo: UserInfo, nihLink: NIHLink) =>
      val parent = context.parent
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
      syncWhiteListResult onComplete {
        case _ => profileResponse pipeTo parent
      }

    case GetNIHStatus(userInfo: UserInfo) =>
      val profileResponse = getProfile(userInfo, false)
      profileResponse pipeTo context.parent

    case GetAndUpdateNIHStatus(userInfo: UserInfo) =>
      getProfile(userInfo, true)
      // do NOT pipe the response back to the parent!

    case SyncWhitelist =>
      syncWhitelist() pipeTo context.parent

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

  def getNIHStatusResponse(pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
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

  def sendNotification(pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    notification: Notification) = {
    pipeline {
      Post(UserService.remotePostNotifyURL, ThurloeNotification(notification.userId, notification.notificationId, notification.toMap))
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

    val nihEnabledFireCloudUsers = NIHWhitelistUtils.getCurrentNihUsernameMap(subjectId) map { mapping =>
      mapping.collect { case (fcUser, nihUser) if whitelist contains nihUser => fcUser }.toSeq
    }

    val memberList = nihEnabledFireCloudUsers map { subjectIds => RawlsGroupMemberList(userSubjectIds = Some(subjectIds))}

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
    val pipeline = addAdminCredentials ~> sendReceive

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
  def getCurrentNihUsernameMap(subjectId: Option[String] = None)(implicit arf: ActorRefFactory, ec: ExecutionContext): Future[Map[String, String]] = {
    val nihUsernames = getAllUserValuesForKey("linkedNihUsername", subjectId)
    val nihExpiretimes = getAllUserValuesForKey("linkExpireTime", subjectId)

    filterForCurrentUsers(nihUsernames, nihExpiretimes)
  }

}
