package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.{FireCloudExceptionWithErrorReport, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.model.{ThurloeNotification, Notification}
import org.broadinstitute.dsde.firecloud.service.UserService
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.http.{OAuth2BearerToken, HttpHeaders, HttpResponse, HttpRequest}
import spray.httpx.TransformerPipelineSupport.WithTransformerConcatenation
import spray.client.pipelining._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import spray.httpx.SprayJsonSupport._

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by mbemis on 10/21/16.
 */
class HttpThurloeDAO ( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends ThurloeDAO with RestJsonClient {

  val fireCloudHeader = HttpHeaders.RawHeader("X-FireCloud-Id", FireCloudConfig.FireCloud.fireCloudId)
  val adminToken = HttpGoogleServicesDAO.getAdminUserAccessToken

  override def sendNotifications(notifications: List[Notification]): Future[Boolean] = {

    val notificationPipeline = addCredentials(OAuth2BearerToken(adminToken)) ~> addHeader(fireCloudHeader) ~> sendReceive
    val thurloeNotifications = notifications.map(n => ThurloeNotification(n.userId, n.replyTo, n.notificationId, n.toMap))

    notificationPipeline(Post(UserService.remotePostNotifyURL, thurloeNotifications)).map { response =>
      if(response.status.isFailure) {
        throw new FireCloudExceptionWithErrorReport(ErrorReport(response))
      }
      else response.status.isSuccess
    }
  }

}
