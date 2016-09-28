package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impRawlsWorkspace
import org.broadinstitute.dsde.firecloud.service.LibraryService._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.RoleSupport
import org.slf4j.LoggerFactory
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


class LibraryService (protected val userInfo: UserInfo, val rawlsDAO: RawlsDAO)(implicit protected val executionContext: ExecutionContext) extends Actor
  with RoleSupport with SprayJsonSupport {

  lazy val log = LoggerFactory.getLogger(getClass)

  implicit val impUserInfo = userInfo

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
        val allOperations = generateAttributeOperations(workspaceResponse.workspace.get.attributes, userAttrs)
        rawlsDAO.patchWorkspaceAttributes(ns, name, allOperations) map (RequestComplete(_))
      }
    }
  }

  /**
    * given a set of existing attributes and a set of new attributes, calculate the attribute operations
    * that need to be performed
    * TODO: existing model uses Map[String,String] which will not work with arrays, booleans, etc!
    * TODO: define how to handle "library:" prefix - is that sent in the inbound request?
    */
  private def generateAttributeOperations(existingAttrs: Map[String, String], newAttrs: JsObject): Seq[AttributeUpdateOperation] = {
    val libraryAttrKeys = existingAttrs.keySet
      .filter(_.startsWith("library:"))
      .map(_.replaceFirst("library:", ""))

    // remove any attributes that currently exist on the workspace, but are not in the user's packet
    // for any array attributes, we remove them and recreate them entirely. Add the array attrs.
    val keysToRemove = libraryAttrKeys.diff(newAttrs.fields.keySet) ++ newAttrs.fields.filter(_._2.isInstanceOf[JsArray]).keySet
    val removeOperations = keysToRemove.map(RemoveAttribute(_)).toSeq

    // TODO: handle numeric/boolean values
    val updateOperations = newAttrs.fields.toSeq flatMap {
      // case (key, value:JsBoolean) => AddUpdateAttribute(key, AttributeString(value.toString()))
      // case (key, value:JsNumber) => AddUpdateAttribute(key, AttributeString(value.toString()))
      case (key, value:JsArray) => value.elements.map{x => AddUpdateAttribute(key, AttributeString(x.toString()))}
      case (key, value:JsValue) => Seq(AddUpdateAttribute(key, AttributeString(value.toString())))
    }

    // handle removals before upserts
    removeOperations ++ updateOperations
  }


}
