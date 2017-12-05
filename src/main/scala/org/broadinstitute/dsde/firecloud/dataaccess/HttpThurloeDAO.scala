package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Trial.UserTrialStatus
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.webservice.UserApiService
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.client.pipelining._
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Created by mbemis on 10/21/16.
 */
class HttpThurloeDAO ( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends ThurloeDAO with RestJsonClient {

  override def getProfile(userInfo: UserInfo): Future[Option[Profile]] = {
    wrapExceptions {
      userAuthedRequest(Get(UserApiService.remoteGetAllURL.format(userInfo.getUniqueId)), false, true)(userInfo) map { response =>
        response.status match {
          case StatusCodes.OK => Some(Profile(unmarshal[ProfileWrapper].apply(response)))
          case StatusCodes.NotFound => None
          case _ => throw new FireCloudException("Unable to get user profile")
        }
      }
    }
  }

  override def getAllUserValuesForKey(key: String): Future[Map[String, String]] = {
    val queryUri = Uri(UserApiService.remoteGetQueryURL).withQuery(Map("key"->key))
    wrapExceptions {
      adminAuthedRequest(Get(queryUri), false, true).map(unmarshal[Seq[ThurloeKeyValue]]).map { tkvs =>
        val resultOptions = tkvs.map { tkv => (tkv.userId, tkv.keyValuePair.flatMap { kvp => kvp.value }) }
        val actualResultsOnly = resultOptions collect { case (Some(firecloudSubjId), Some(thurloeValue)) => (firecloudSubjId, thurloeValue) }
        actualResultsOnly.toMap
      }
    }
  }

  /**
    * Save KVPs for myself - the KVPs will be saved to the same user that authenticates the call.
    * @param userInfo contains the userid for which to save KVPs and that user's auth token
    * @param keyValues the KVPs to save
    * @return success/failure of save
    */
  override def saveKeyValues(userInfo: UserInfo, keyValues: Map[String, String]): Future[Try[Unit]] =
    saveKeyValues(userInfo.id, userInfo, keyValues)

  /**
    * Save KVPs for a different user - the KVPs will be saved to the "forUserId" user,
    * but the call to Thurloe will be authenticated as the "callerToken" user.
    *
    * @param forUserId the userid of the user for which to save KVPs
    * @param callerToken auth token of the user making the call
    * @return success/failure of save
    */
  override def saveKeyValues(forUserId: String, callerToken: WithAccessToken, keyValues: Map[String, String]): Future[Try[Unit]] = {
    val thurloeKeyValues = ThurloeKeyValues(Option(forUserId), Option(keyValues.map { case (key, value) => FireCloudKeyValue(Option(key), Option(value)) }.toSeq))
    wrapExceptions {
      userAuthedRequest(Post(UserApiService.remoteSetKeyURL, thurloeKeyValues), false, true)(callerToken) map { response =>
        if(response.status.isSuccess) Try(())
        else Try(throw new FireCloudException(s"Unable to update user profile"))
      }
    }
  }

  override def saveProfile(userInfo: UserInfo, profile: BasicProfile): Future[Unit] = {
    val profilePropertyMap = profile.propertyValueMap ++ Map("email" -> userInfo.userEmail)
    saveKeyValues(userInfo, profilePropertyMap).map(_ => ())
  }
  /**
    * get the UserTrialStatus associated with a specific user.
    *
    * @param forUserId the subjectid of the user whose trial status to get
    * @param callerToken the OAuth token of the person making the API call
    * @return the trial status for the specified user, or None if trial status could not be determined.
    */
  override def getTrialStatus(forUserId: String, callerToken: WithAccessToken): Future[Option[UserTrialStatus]] = {
    wrapExceptions {
      userAuthedRequest(Get(UserApiService.remoteGetAllURL.format(forUserId)), false, true)(callerToken) map { response =>
        response.status match {
          case StatusCodes.OK => Some(UserTrialStatus(unmarshal[ProfileWrapper].apply(response)))
          case StatusCodes.NotFound => None
          case _ => throw new FireCloudException("Unable to get user trial status")
        }
      }
    }
  }

  /**
    * set the UserTrialStatus for a specific user
    *
    * @param forUserId the subjectid of the user whose trial status to set
    * @param callerToken the OAuth token of the person making the API call
    * @param trialStatus the trial status to save for the specified user
    * @return success/failure of whether or not the status saved correctly
    */
  override def saveTrialStatus(forUserId: String, callerToken: WithAccessToken, trialStatus: UserTrialStatus): Future[Try[Unit]] = {
    saveKeyValues(forUserId, callerToken, UserTrialStatus.toKVPs(trialStatus))
  }


  private def wrapExceptions[T](codeBlock: => Future[T]): Future[T] = {
    codeBlock.recover {
      case t: Throwable => {
        throw new FireCloudExceptionWithErrorReport(ErrorReport.apply(StatusCodes.InternalServerError, t))
      }
    }
  }

  override def status: Future[SubsystemStatus] = {
    val thurloeStatus = unAuthedRequestToObject[ThurloeStatus](Get(Uri(FireCloudConfig.Thurloe.baseUrl).withPath(Uri.Path("/status"))), useFireCloudHeader = true)
    thurloeStatus map { thurloeStatus =>
      thurloeStatus.status match {
        case "up" => SubsystemStatus(ok = true, None)
        case "down" => SubsystemStatus(ok = false, thurloeStatus.error.map(List(_)))
      }
    }
  }

}
