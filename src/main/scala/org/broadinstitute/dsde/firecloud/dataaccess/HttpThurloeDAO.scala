package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Project.UserTrialStatus
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.webservice.UserApiService
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
/**
  * Created by mbemis on 10/21/16.
  */
class HttpThurloeDAO ( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends ThurloeDAO with RestJsonClient with SprayJsonSupport {

  implicit val materializer: Materializer

  override def getAllKVPs(forUserId: String, callerToken: WithAccessToken): Future[Option[ProfileWrapper]] = {
    wrapExceptions {
      val req = userAuthedRequest(Get(UserApiService.remoteGetAllURL.format(forUserId)), useFireCloudHeader = true, label = Some("HttpThurloeDAO.getAllKVPs"))(callerToken)

      req flatMap { response =>
        response.status match {
          case StatusCodes.OK => Unmarshal(response).to[ProfileWrapper].map(Option(_))
          case StatusCodes.NotFound => Future.successful(None)
          case _ => throw new FireCloudException("Unable to get user KVPs from profile service")
        }
      }
    }
  }

  override def getAllUserValuesForKey(key: String): Future[Map[String, String]] = {
    val queryUri = Uri(UserApiService.remoteGetQueryURL).withQuery(Query(("key"->key)))
    wrapExceptions {
      adminAuthedRequest(Get(queryUri), false, true, label = Some("HttpThurloeDAO.getAllUserValuesForKey")).flatMap(x => Unmarshal(x).to[Seq[ThurloeKeyValue]]).map { tkvs =>
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
      userAuthedRequest(Post(UserApiService.remoteSetKeyURL, thurloeKeyValues), compressed = false, useFireCloudHeader = true, label = Some("HttpThurloeDAO.saveKeyValues"))(callerToken) map { response =>
        if(response.status.isSuccess) Try(())
        else Try(throw new FireCloudException(s"Unable to update user profile"))
      }
    }
  }

  override def saveProfile(userInfo: UserInfo, profile: BasicProfile): Future[Unit] = {
    val profilePropertyMap = profile.propertyValueMap
    saveKeyValues(userInfo, profilePropertyMap).map(_ => ())
  }

  /**
    * get the UserTrialStatus associated with a specific user.
    *
    * @param forUserId the subjectid of the user whose trial status to get
    * @param callerToken the OAuth token of the person making the API call
    * @return the trial status for the specified user, or a default UserTrialStatus object
    */
  override def getTrialStatus(forUserId: String, callerToken: WithAccessToken): Future[UserTrialStatus] = {
    getAllKVPs(forUserId, callerToken) map {
      case Some(wrapper) =>
        val status = UserTrialStatus(wrapper)
        assert(forUserId == status.userId, "status id does not match!")
        status
      case None =>
        throw new FireCloudException("Unable to get user trial status")
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

  override def bulkUserQuery(userIds: List[String], keySelection: List[String]): Future[List[ProfileWrapper]] = {
    val userIdParams:List[(String,String)] = userIds.map(("userId", _))
    val keyParams:List[(String,String)] = keySelection.map(("key", _))

    val allQueryParams = keyParams ++ userIdParams

    val queryUri = Uri(UserApiService.remoteGetQueryURL).withQuery(Query(allQueryParams.toMap))

    // default uri length for Spray - which Thurloe uses - is 2048 chars
    assert(queryUri.toString().length <  2048, s"generated url is too long at ${queryUri.toString().length} chars.")

    val req = adminAuthedRequest(Get(queryUri), useFireCloudHeader = true,label = Some("HttpThurloeDAO.bulkUserQuery"))

    req flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          val profileKVPsF:Future[List[ProfileKVP]] = Unmarshal(response).to[List[ProfileKVP]]
          val groupedByUserF:Future[Map[String, List[ProfileKVP]]] = profileKVPsF.map(x => x.groupBy(_.userId))
          groupedByUserF.map{ groupedByUser =>
            groupedByUser.map {
              case (userId: String, kvps: List[ProfileKVP]) => ProfileWrapper(userId, kvps.map(_.keyValuePair))
            }.toList
          }

        case _ => throw new FireCloudException(s"Unable to execute bulkUserQuery from profile service: ${response.status} $response")
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
