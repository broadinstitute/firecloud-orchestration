package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.UserService
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.{FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.ErrorReport
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
      userAuthedRequest(Get(UserService.remoteGetAllURL.format(userInfo.getUniqueId)), false, true)(userInfo) map { response =>
        response.status match {
          case StatusCodes.OK => Some(Profile(unmarshal[ProfileWrapper].apply(response)))
          case StatusCodes.NotFound => None
          case _ => throw new FireCloudException("Unable to get user profile")
        }
      }
    }
  }

  override def getAllUserValuesForKey(key: String): Future[Map[String, String]] = {
    val queryUri = Uri(UserService.remoteGetQueryURL).withQuery(Map("key"->key))
    wrapExceptions {
      adminAuthedRequest(Get(queryUri), false, true).map(unmarshal[Seq[ThurloeKeyValue]]).map { tkvs =>
        val resultOptions = tkvs.map { tkv => (tkv.userId, tkv.keyValuePair.flatMap { kvp => kvp.value }) }
        val actualResultsOnly = resultOptions collect { case (Some(firecloudSubjId), Some(thurloeValue)) => (firecloudSubjId, thurloeValue) }
        actualResultsOnly.toMap
      }
    }
  }

  override def saveKeyValues(userInfo: UserInfo, keyValues: Map[String, String]): Future[Try[Unit]] = {
    val thurloeKeyValues = ThurloeKeyValues(Some(userInfo.getUniqueId), Some(keyValues.map { case (key, value) => FireCloudKeyValue(Some(key), Some(value)) }.toSeq))
    wrapExceptions {
      userAuthedRequest(Post(UserService.remoteSetKeyURL, thurloeKeyValues), false, true)(userInfo) map { response =>
        if(response.status.isSuccess) Try(())
        else Try(throw new FireCloudException(s"Unable to update user profile"))
      }
    }
  }

  override def saveProfile(userInfo: UserInfo, profile: BasicProfile): Future[Unit] = {
    val profilePropertyMap = profile.propertyValueMap ++ Map("email" -> userInfo.userEmail)
    saveKeyValues(userInfo, profilePropertyMap).map(_ => ())
  }

  def wrapExceptions[T](codeBlock: => Future[T]): Future[T] = {
    codeBlock.recover {
      case t: Throwable => {
        throw new FireCloudExceptionWithErrorReport(ErrorReport.apply(StatusCodes.InternalServerError, t))
      }
    }
  }
}
