package org.broadinstitute.dsde.firecloud.core

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes._
import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.DsdeHttpDAO
import org.broadinstitute.dsde.firecloud.model.MethodRepository.ACLNames._
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, EntityAccessControlAgora, FireCloudPermission, MethodAclPair}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.HttpClientUtilsStandard
import org.broadinstitute.dsde.firecloud.webservice.MethodsApiServiceUrls
import org.broadinstitute.dsde.rawls.model.MethodRepoMethod

import scala.concurrent.{ExecutionContext, Future}

object AgoraPermissionHandler { //rename to AgoraPermissionService

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

  def constructor()(userInfo: WithAccessToken)(implicit executionContext: ExecutionContext) =
    new AgoraPermissionActor(userInfo)

}

class AgoraPermissionActor(userInfo: WithAccessToken)(implicit val executionContext: ExecutionContext) extends LazyLogging
  with FireCloudRequestBuilding with MethodsApiServiceUrls with DsdeHttpDAO {

  import spray.json.DefaultJsonProtocol._

  implicit val materializer: Materializer

//  override val http = Http(system)
//  override val httpClientUtils = HttpClientUtilsStandard()

  def GetAgoraPermission(url: String) = executeAndCreateAgoraResponse(Get(url))
  def CreateAgoraPermission(url: String, agoraPermissions: List[AgoraPermission]) = executeAndCreateAgoraResponse(Post(url, agoraPermissions))
  def UpsertAgoraPermissions(inputs: List[EntityAccessControlAgora]) = multiUpsert(inputs)

  def executeAndCreateAgoraResponse(request: HttpRequest): Future[PerRequestMessage] = {

    executeRequestWithToken[List[AgoraPermission]](userInfo.accessToken)(request).map { agoraPermissions =>
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

  def multiUpsert(inputs: List[EntityAccessControlAgora]): Future[PerRequestMessage] = {

    val upsertRequest = Put(remoteMultiPermissionsUrl, inputs)

    executeRequestWithToken[List[EntityAccessControlAgora]](userInfo.accessToken)(upsertRequest).map { agoraResponse =>
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
