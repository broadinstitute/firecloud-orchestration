package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.service.LibraryService
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by davidan on 9/23/16.
  */
class HttpRawlsDAO( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends RawlsDAO with RestJsonClient {

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest( Get(rawlsAdminUrl) )(userInfo) map { response =>
      response.status match {
        case OK => true
        case NotFound => false
        case _ => throw new FireCloudExceptionWithErrorReport(ErrorReport(response))
      }
    }
  }

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest( Get(rawlsCuratorUrl) )(userInfo) map { response =>
      response.status match {
        case OK => true
        case NotFound => false
        case _ => throw new FireCloudExceptionWithErrorReport(ErrorReport(response))
      }
    }
  }

  override def getWorkspace(ns: String, name: String)(implicit userInfo: UserInfo): Future[RawlsWorkspaceResponse] =
    requestToObject[RawlsWorkspaceResponse]( Get(getWorkspaceUrl(ns, name)) )

  override def patchWorkspaceAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userInfo: UserInfo): Future[RawlsWorkspace] = {
    import spray.json.DefaultJsonProtocol._
    import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperationFormat
    requestToObject[RawlsWorkspace]( Patch(getWorkspaceUrl(ns, name), attributeOperations) )
  }

  // TODO: use rawls query-by-attribute once that exists
  override def getAllLibraryPublishedWorkspaces: Future[Seq[RawlsWorkspace]] = {
    val adminToken = HttpGoogleServicesDAO.getAdminUserAccessToken

    val allPublishedPipeline = addCredentials(OAuth2BearerToken(adminToken)) ~> sendReceive
    allPublishedPipeline(Get(rawlsAdminWorkspaces)) map {response =>
      response.entity.as[Seq[RawlsWorkspace]] match {
        case Right(srw) =>
          logger.info("admin workspace list got: " + srw.length + " raw workspaces")
          val published = srw.collect {
            case rw:RawlsWorkspace if rw.attributes.getOrElse(LibraryService.publishedFlag, "false").toBoolean => rw
          }
          logger.info("admin workspace list collected: " + published.length + " published workspaces")
          published
        case Left(error) =>
          logger.warn("Could not unmarshal: " + error.toString)
          throw new FireCloudExceptionWithErrorReport(ErrorReport(response)) // replay the root exception
      }
    }
  }

  private def getWorkspaceUrl(ns: String, name: String) = FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/%s/%s".format(ns, name)


}
