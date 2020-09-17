package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, EntityAccessControlAgora, FireCloudPermission, MethodAclPair}
import org.broadinstitute.dsde.firecloud.model.MethodRepository.ACLNames._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.RequestCompleteWithErrorReport
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.webservice.MethodsApiServiceUrls
import org.broadinstitute.dsde.rawls.model.MethodRepoMethod
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http.{HttpResponse, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext

import scala.concurrent.Future

object AgoraPermissionHandler {
//  case class Get(url: String)
//  case class Post(url: String, agoraPermissions: List[AgoraPermission])
  case class MultiUpsert(inputs: List[EntityAccessControlAgora])
  def props(requestContext: RequestContext): Props = Props(new GetEntitiesWithTypeActor(requestContext))

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
}

class AgoraPermissionActor (requestContext: RequestContext) extends Actor
  with FireCloudRequestBuilding with MethodsApiServiceUrls {

  implicit val system = context.system
  import system.dispatcher
  import spray.json.DefaultJsonProtocol._

  val log = Logging(system, getClass)
  val pipeline = authHeaders(requestContext) ~> sendReceive

  def receive = {
    case AgoraPermissionHandler.Get(url: String) =>
      createAgoraResponse(pipeline { Get(url) }) pipeTo context.parent
    case AgoraPermissionHandler.Post(url: String, agoraPermissions: List[AgoraPermission]) =>
      createAgoraResponse(pipeline { Post(url, agoraPermissions) }) pipeTo context.parent
    case AgoraPermissionHandler.MultiUpsert(inputs: List[EntityAccessControlAgora]) =>
      multiUpsert(inputs) pipeTo context.parent
    case _ =>
      Future(RequestComplete(StatusCodes.BadRequest)) pipeTo context.parent
  }

  def createAgoraResponse(permissionListFuture: Future[HttpResponse]): Future[PerRequestMessage] = {
    permissionListFuture.map {response =>
        response.status match {
          case StatusCodes.OK =>
            try {
              val agoraPermissions = unmarshal[List[AgoraPermission]].apply(response)
              val fireCloudPermissions = agoraPermissions.map(x => x.toFireCloudPermission)
              RequestComplete(OK, fireCloudPermissions)
            } catch {
              // TODO: more specific and graceful error-handling
              case e: Exception =>
                RequestCompleteWithErrorReport(InternalServerError, "Failed to interpret methods " +
                  "server response: " + e.getMessage)
            }
          case x =>
            RequestCompleteWithErrorReport(x, response.entity.asString)
        }
      }.recoverWith {
        case e: Throwable => Future(RequestCompleteWithErrorReport(InternalServerError, e.getMessage))
      }
  }

  def multiUpsert(inputs: List[EntityAccessControlAgora]): Future[PerRequestMessage] = {

    val respFuture:Future[HttpResponse] = pipeline( Put(remoteMultiPermissionsUrl, inputs) )

    respFuture.map { response =>
      response.status match {
        case StatusCodes.OK =>
          try {
            val agoraResponse = unmarshal[List[EntityAccessControlAgora]].apply(response)
            val fcResponse = agoraResponse.map {eaca =>
              val mrm = MethodRepoMethod(eaca.entity.namespace.get, eaca.entity.name.get, eaca.entity.snapshotId.get)
              MethodAclPair(mrm, eaca.acls.map(_.toFireCloudPermission), eaca.message)
            }
            RequestComplete(OK, fcResponse)
          } catch {
            case e: Exception => RequestCompleteWithErrorReport(InternalServerError, "Failed to interpret methods " +
              "server response: " + e.getMessage)
          }
        case x => RequestCompleteWithErrorReport(x, response.entity.asString)
      }
    }.recoverWith {
      case e: Throwable => Future(RequestCompleteWithErrorReport(InternalServerError, e.getMessage))
    }
  }

}
