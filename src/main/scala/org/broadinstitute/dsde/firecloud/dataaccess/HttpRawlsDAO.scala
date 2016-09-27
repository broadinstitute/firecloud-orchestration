package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.client.pipelining._
import spray.http._
import spray.http.StatusCodes._
import spray.httpx.unmarshalling._
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by davidan on 9/23/16.
  */
class HttpRawlsDAO( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext ) extends RawlsDAO {

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest( Get(rawlsAdminUrl) )(userInfo) map { response =>
      response.status match {
        case OK => true
        case NotFound => false
        case _ => throw new FireCloudExceptionWithErrorReport(ErrorReport(response)) // replay the root exception
      }
    }
  }

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest( Get(rawlsCuratorUrl) )(userInfo) map { response =>
      response.status match {
        case OK => true
        case NotFound => false
        case _ => throw new FireCloudExceptionWithErrorReport(ErrorReport(response)) // replay the root exception
      }
    }
  }

  override def getWorkspace(ns: String, name: String)(implicit userInfo: UserInfo): Future[RawlsWorkspaceResponse] = {
    userAuthedRequest( Get(getWorkspaceUrl(ns, name)) ) map {response =>
      response.entity.as[RawlsWorkspaceResponse] match {
        case Right(rwr) => rwr
        case Left(error) => throw new FireCloudExceptionWithErrorReport(ErrorReport(response)) // replay the root exception
      }
    }
  }

  override def patchWorkspaceAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userInfo: UserInfo): Future[RawlsWorkspace] = {
    import spray.json.DefaultJsonProtocol._
    import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperationFormat

    userAuthedRequest( Patch(getWorkspaceUrl(ns, name), attributeOperations) ) map {response =>
      response.entity.as[RawlsWorkspace] match {
        case Right(rw) => rw
        case Left(error) => throw new FireCloudExceptionWithErrorReport(ErrorReport(response)) // replay the root exception
      }
    }
  }


  private def getWorkspaceUrl(ns: String, name: String) = FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/%s/%s".format(ns, name)

  // TODO: make this a globally-available util method
  private def userAuthedRequest(req: HttpRequest)(implicit userInfo: UserInfo): Future[HttpResponse] = {
    val pipeline = addCredentials(userInfo.accessToken) ~> sendReceive
    pipeline(req)
  }

}
