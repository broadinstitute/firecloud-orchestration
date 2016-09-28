package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, _}
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impRawlsWorkspaceResponse
import org.broadinstitute.dsde.firecloud.service.LibraryService._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.RoleSupport
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.httpx.unmarshalling._
import spray.httpx.PipelineException
import spray.httpx.SprayJsonSupport._
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

class LibraryService (protected val userInfo: UserInfo, val rawlsDAO: RawlsDAO)(implicit protected val executionContext: ExecutionContext) extends Actor with RoleSupport {

  lazy val log = LoggerFactory.getLogger(getClass)

  override def receive = {
    case UpdateAttributes(ns: String, name: String, attrs: JsValue) => asCurator {updateAttributes(ns, name, attrs)} pipeTo sender
  }

  def updateAttributes(ns: String, name: String, attrs: JsValue): Future[PerRequestMessage] = {
    // spray routing can only (easily) make a JsValue; we need to work with a JsObject
    val userAttrs = attrs.asJsObject

    // TODO: schema-validate user input

    getWorkspace(ns, name) flatMap { workspaceResponse =>
      // verify owner on workspace
      if (!workspaceResponse.accessLevel.contains("OWNER")) {
        Future(RequestCompleteWithErrorReport(Forbidden, "must be an owner"))
      } else {
        val libraryAttrKeys = workspaceResponse.workspace.get.attributes.keySet.filter(_.startsWith("library:"))

        // remove any attributes that currently exist on the workspace, but are not in the user's packet
        // for any array attributes, we remove them and recreate them entirely. Add the array attrs.
        val keysToRemove = libraryAttrKeys.diff(userAttrs.fields.keySet) ++ userAttrs.fields.filter(_._2.isInstanceOf[JsArray]).keySet
        val removeOperations = keysToRemove.map(RemoveAttribute(_)).toSeq

        // TODO: handle numeric/boolean values
        val updateOperations = userAttrs.fields.toSeq flatMap {
          // case (key, value:JsBoolean) => AddUpdateAttribute(key, AttributeString(value.toString()))
          // case (key, value:JsNumber) => AddUpdateAttribute(key, AttributeString(value.toString()))
          case (key, value:JsArray) => value.elements.map{x => AddUpdateAttribute(key, AttributeString(x.toString()))}
          case (key, value:JsValue) => Seq(AddUpdateAttribute(key, AttributeString(value.toString())))
        }

        // handle removals before upserts
        val allOperations: Seq[AttributeUpdateOperation] = removeOperations ++ updateOperations

        // spray marshalling is so very sensitive to location of imports!
        import spray.json.DefaultJsonProtocol._
        import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperationFormat

        // update attributes in rawls
        val attrUpdatePipeline = addCredentials(userInfo.accessToken) ~> sendReceive
        attrUpdatePipeline(Patch(getWorkspaceUrl(ns, name), allOperations)) flatMap { attrUpdateResponse =>
          attrUpdateResponse.status match {
            // respond with updated workspace|updated attributes
            case OK =>
              getWorkspace(ns, name) map {ws => RequestComplete(OK, ws)}
              // RequestComplete(OK, getWorkspace(ns, name))
              // RequestComplete(OK)
            case x => Future(RequestCompleteWithErrorReport(x, "Could not update workspace", Seq(ErrorReport(attrUpdateResponse))))
          }
        }
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
