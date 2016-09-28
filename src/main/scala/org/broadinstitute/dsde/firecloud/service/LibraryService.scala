package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impRawlsWorkspace
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.LibraryService._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.RoleSupport
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport
import spray.json._


import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by davidan on 9/23/16.
  */

object LibraryService {
  sealed trait LibraryServiceMessage
  case class UpdateAttributes(ns: String, name: String, attrs: JsValue) extends LibraryServiceMessage

  def props(libraryServiceConstructor: UserInfo => LibraryService, userInfo: UserInfo): Props = {
    Props(libraryServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new LibraryService(userInfo, app.rawlsDAO)
}


class LibraryService (protected val argUserInfo: UserInfo, val rawlsDAO: RawlsDAO)(implicit protected val executionContext: ExecutionContext) extends Actor
  with LibraryServiceSupport with RoleSupport with SprayJsonSupport {

  lazy val log = LoggerFactory.getLogger(getClass)

  implicit val userInfo = argUserInfo

  override def receive = {
    case UpdateAttributes(ns: String, name: String, attrs: JsValue) => asCurator {updateAttributes(ns, name, attrs)} pipeTo sender
  }

  def updateAttributes(ns: String, name: String, attrs: JsValue): Future[PerRequestMessage] = {
    // spray routing can only (easily) make a JsValue; we need to work with a JsObject
    // TODO: handle exceptions on this cast

    val userAttrs = attrs.asJsObject

    // TODO: schema-validate user input

    rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
      // verify owner on workspace
      if (!workspaceResponse.accessLevel.contains("OWNER")) {
        Future(RequestCompleteWithErrorReport(Forbidden, "must be an owner"))
      } else {
        // this is technically vulnerable to a race condition in which the workspace attributes have changed
        // between the time we retrieved them and here, where we update them.
        val allOperations = generateAttributeOperations(workspaceResponse.workspace.get.attributes, userAttrs)
        rawlsDAO.patchWorkspaceAttributes(ns, name, allOperations) map (RequestComplete(_))
      }
    }
      /* recover {
      case pe:PipelineException => RequestCompleteWithErrorReport(InternalServerError, "Error with workspace", pe)
      case _ => RequestCompleteWithErrorReport(InternalServerError, "Unknown error")
    } */
  }

  // TODO: should be in a rawls dao, not library dao
  private def getWorkspace(ns: String, name: String): Future[RawlsWorkspaceResponse] = {
    val workspacePipeline = addCredentials(userInfo.accessToken) ~> sendReceive ~> unmarshal[RawlsWorkspaceResponse]
    workspacePipeline(Get(getWorkspaceUrl(ns, name)))
  }

  private def getWorkspaceUrl(ns: String, name: String) = FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/%s/%s".format(ns, name)

}
