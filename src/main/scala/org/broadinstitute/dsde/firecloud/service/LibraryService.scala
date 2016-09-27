package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.{AttributeString, ErrorReport, RawlsWorkspaceResponse, UserInfo}
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations._
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

    import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impRawlsWorkspaceResponse

    val userAttrs = attrs.asJsObject

    // TODO: schema-validate user input

    // TODO: factor out get workspace into own method
    val workspacePipeline = addCredentials(userInfo.accessToken) ~> sendReceive ~> unmarshal[RawlsWorkspaceResponse]
    val workspaceUrl = FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/%s/%s".format(ns, name)
    workspacePipeline(Get(workspaceUrl)) map {workspaceResponse =>
      // TODO: verify owner on workspace - factor out
      if (!workspaceResponse.accessLevel.contains("OWNER")) {
        throw new FireCloudExceptionWithErrorReport(errorReport=ErrorReport(spray.http.StatusCodes.Forbidden, "must be an owner"))
      } else {
        val libraryAttrKeys = workspaceResponse.workspace.get.attributes.keySet.filter(_.startsWith("library:"))

        // remove any attributes that currently exist on the workspace, but are not in the user's packet
        val keysToRemove = libraryAttrKeys.diff(userAttrs.fields.keySet)
        // anything the user posted should be updated, so no extra calculation necessary

        val removeOperations = (keysToRemove ++ userAttrs.fields.filter(_._2.isInstanceOf[JsArray]).keySet).map(RemoveAttribute(_))

        // TODO: handle numeric/boolean values
        val updateOperations = userAttrs.fields.seq flatMap {
          // case (key, value:JsBoolean) => AddUpdateAttribute(key, AttributeString(value.toString()))
          // case (key, value:JsNumber) => AddUpdateAttribute(key, AttributeString(value.toString()))
          case (key, value:JsArray) => value.elements.map{x => AddUpdateAttribute(key, AttributeString(x.toString()))}
          case (key, value:JsValue) => Seq(AddUpdateAttribute(key, AttributeString(value.toString())))
        }

        val allOperations = removeOperations ++ updateOperations

        // TODO: update attributes in rawls


        // TODO: respond with updated workspace|updated attributes
        RequestComplete(NotImplemented, "Still a work in progress")
      }
    } recover {
      case pe:PipelineException => throw new FireCloudException("Error with workspace", pe)
    }
  }

}
