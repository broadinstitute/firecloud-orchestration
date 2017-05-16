package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import com.typesafe.config.Config
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, CopyPermissions, EntityId}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{AgoraStatus, SubsystemStatus, UserInfo}
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.client.pipelining._
import spray.http.Uri
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

class HttpAgoraDAO(config: FireCloudConfig.Agora.type)(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends AgoraDAO with RestJsonClient {

  private def getNamespaceUrl(ns: String, entity: String): String = {
    s"${config.authUrl}/$entity/$ns/permissions"
  }

  private def getMethodPermissionsUrl(ns: String, name: String, snapshotId: Int): String = {
    s"${config.authUrl}/methods/$ns/$name/$snapshotId/permissions"
  }

  override def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] =
    authedRequestToObject[List[AgoraPermission]]( Get(getNamespaceUrl(ns, entity)) )

  override def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] =
    authedRequestToObject[List[AgoraPermission]]( Post(getNamespaceUrl(ns, entity), perms) )

  override def getMethodPermissions(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] =
    authedRequestToObject[List[AgoraPermission]]( Get(getMethodPermissionsUrl(ns, name, snapshotId)) )

  override def postMethodPermissions(ns: String, name: String, snapshotId: Int, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] =
    authedRequestToObject[List[AgoraPermission]]( Post(getMethodPermissionsUrl(ns, name, snapshotId), perms) )

  override def status: Future[SubsystemStatus] = {
    val agoraStatus = unAuthedRequestToObject[AgoraStatus](Get(Uri(config.baseUrl).withPath(Uri.Path("/status"))))

    agoraStatus map { agoraStatus =>
      agoraStatus.status match {
        case "up" => SubsystemStatus(true)
        case _ => SubsystemStatus(false, if (agoraStatus.message.nonEmpty) Some(agoraStatus.message) else None)
      }
    }
  }

}
