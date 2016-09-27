package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.client.pipelining._
import spray.http.{HttpEntity, OAuth2BearerToken}
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

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = {
    val pipeline = addCredentials(userInfo.accessToken)  ~> sendReceive
    pipeline{Get(rawlsCuratorUrl)} map { response =>
      response.status match {
        case OK => true
        case NotFound => false
        case _ => throw new FireCloudExceptionWithErrorReport(ErrorReport(response)) // replay the root exception
      }
    }
  }

  override def withWorkspaceResponse(wsid: WorkspaceName)(implicit userInfo: UserInfo): Future[RawlsWorkspaceResponse] = {
    val workspacePath = rawlsWorkspacesRoot + "/%s/%s".format(wsid.namespace.get, wsid.name.get)
    val pipeline = addCredentials(userInfo.accessToken)  ~> sendReceive
    pipeline{Get(workspacePath)} map {response =>
      response.entity.as[RawlsWorkspaceResponse] match {
        case Right(rwr) => rwr
        case Left(error) => throw new FireCloudExceptionWithErrorReport(ErrorReport(response)) // replay the root exception
      }
    }
  }


}
