package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.AgoraDAO
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.ACLNames._
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.{AgoraPermission, EntityAccessControlAgora, FireCloudPermission, MethodAclPair}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.rawls.model.MethodRepoMethod
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object AgoraPermissionService {

  // convenience method to translate a FireCloudPermission object to an AgoraPermission object
  def toAgoraPermission(fireCloudPermission: FireCloudPermission):AgoraPermission = {
    AgoraPermission(Some(fireCloudPermission.user), Some(toAgoraRoles(fireCloudPermission.role)))
  }

  // convenience method to translate an AgoraPermission to a FireCloudPermission object
  def toFireCloudPermission(agoraPermission: AgoraPermission):FireCloudPermission = {
    // if the Agora permission has a None/empty/whitespace user, the following will throw
    // an IllegalArgumentException trying to create the FireCloudPermission. That exception
    // will be caught elsewhere.
    FireCloudPermission(agoraPermission.user.getOrElse(""), toFireCloudRole(agoraPermission.roles))
  }

  // translation between a FireCloud role and a list of Agora roles
  def toAgoraRoles(fireCloudRole:String) = {
    fireCloudRole match {
      case NoAccess => ListNoAccess
      case Reader => ListReader
      case Owner => ListOwner // Could use "All" instead but this is more precise
      case _ => ListNoAccess
    }
  }

  // translation between a list of Agora roles and a FireCloud role
  def toFireCloudRole(agoraRoles:Option[List[String]]) = {
    agoraRoles match {
      case None => NoAccess
      case Some(r) => {
        r.sorted match {
          case ListNoAccess => NoAccess
          case ListReader => Reader
          case ListOwner => Owner
          case ListAll => Owner
          case _ => NoAccess
        }
      }
    }
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new AgoraPermissionService(userInfo, app.agoraDAO)

}

class AgoraPermissionService(userInfo: UserInfo, val agoraDAO: AgoraDAO)(implicit val executionContext: ExecutionContext) extends LazyLogging with SprayJsonSupport {

  def getAgoraPermission(url: String): Future[PerRequestMessage] = {
    agoraDAO.getPermission(url)(userInfo).map { agoraPermissions =>
      try {
        val fireCloudPermissions = agoraPermissions.map(_.toFireCloudPermission)
        RequestComplete(OK, fireCloudPermissions)
      } catch {
        // TODO: more specific and graceful error-handling
        case e: Exception =>
          RequestCompleteWithErrorReport(InternalServerError, "Failed to interpret methods " +
            "server response: " + e.getMessage)
      }
    } recoverWith {
      case e: Throwable => Future(RequestCompleteWithErrorReport(InternalServerError, e.getMessage))
    }
  }

  def createAgoraPermission(url: String, agoraPermissions: List[AgoraPermission]): Future[PerRequestMessage] = {
    agoraDAO.createPermission(url, agoraPermissions)(userInfo).map { agoraPermissions =>
      try {
        val fireCloudPermissions = agoraPermissions.map(_.toFireCloudPermission)
        RequestComplete(OK, fireCloudPermissions)
      } catch {
        // TODO: more specific and graceful error-handling
        case e: Exception =>
          RequestCompleteWithErrorReport(InternalServerError, "Failed to interpret methods " +
            "server response: " + e.getMessage)
      }
    } recoverWith {
      case e: Throwable => Future(RequestCompleteWithErrorReport(InternalServerError, e.getMessage))
    }
  }

  def batchInsertAgoraPermissions(inputs: List[EntityAccessControlAgora]): Future[PerRequestMessage] = {
    agoraDAO.batchCreatePermissions(inputs)(userInfo).map { agoraResponse =>
      try {
        val fcResponse = agoraResponse.map { eaca =>
          val mrm = MethodRepoMethod(eaca.entity.namespace.get, eaca.entity.name.get, eaca.entity.snapshotId.get)
          MethodAclPair(mrm, eaca.acls.map(_.toFireCloudPermission), eaca.message)
        }
        RequestComplete(OK, fcResponse)
      } catch {
        case e: Exception => RequestCompleteWithErrorReport(InternalServerError, "Failed to interpret methods " +
          "server response: " + e.getMessage)
      }
    }.recoverWith {
      case e: Throwable => Future(RequestCompleteWithErrorReport(InternalServerError, e.getMessage))
    }
  }
}
