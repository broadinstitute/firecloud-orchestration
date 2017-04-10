package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import com.typesafe.config.Config
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.MethodRepository.AgoraPermission
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.client.pipelining._
import spray.http.{Uri}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

class HttpAgoraDAO(agoraAuthUrl: String, agoraBaseUrl: String)(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends AgoraDAO with RestJsonClient {

  private def getNamespaceUrl(ns: String, entity: String): String = {
    s"$agoraAuthUrl/$entity/$ns/permissions"
  }

  override def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] =
    authedRequestToObject[List[AgoraPermission]]( Get(getNamespaceUrl(ns, entity)) )

  override def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] =
    authedRequestToObject[List[AgoraPermission]]( Post(getNamespaceUrl(ns, entity), perms) )

  def status: Future[(Boolean, Option[String])] = {
    val response = unAuthedRequest(Get(Uri(agoraBaseUrl).withPath(Uri.Path("/status"))))

    response map { response =>
      response.status match {
        case OK => (true, None)
        case _ => (false, Some("failure"))

      }
    }
  }

}
