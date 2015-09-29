package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, FireCloudPermission}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.HttpClient
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http.{Uri, HttpResponse, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object AgoraPermissionHandler {
  case class Delete(url: String, user: String)
  case class Get(url: String)
  case class Post(url: String, agoraPermissions: List[AgoraPermission])
  case class Put(url: String, agoraPermission: AgoraPermission)
  def props(requestContext: RequestContext): Props = Props(new GetEntitiesWithTypeActor(requestContext))

  // TODO: add tests for all of the following methods!

  // convenience method to translate a FireCloudPermission object to an AgoraPermission object
  def toAgoraPermission(fireCloudPermission: FireCloudPermission):AgoraPermission = {
    fireCloudPermission.user match {
      case None => {
        // TODO: should throw an error if user is not specified
        AgoraPermission(None, Some(List.empty))
      }
      case Some(u) => AgoraPermission(Some(u), Some(toAgoraRoles(fireCloudPermission.role)))
    }
  }

  // convenience method to translate an AgoraPermission to a FireCloudPermission object
  def toFireCloudPermission(agoraPermission: AgoraPermission):FireCloudPermission = {
    agoraPermission.user match {
      case None => {
        // TODO: should throw an error if user is not specified
        FireCloudPermission(None, Some("NO ACCESS"))
      }
      case Some(u) => FireCloudPermission(Some(u), Some(toFireCloudRole(agoraPermission.roles)))
    }
  }

  // translation between a FireCloud role and a list of Agora roles
  def toAgoraRoles(fireCloudRole:Option[String]) = {
    fireCloudRole match {
      case None => List("Nothing")
      case Some("OWNER") => List("Read","Write","Create","Redact","Manage") // Could use "All" instead but this is more precise
      case Some("READER") => List("Read")
      case Some("NO ACCESS") => List("Nothing")
      case _ => List("Nothing") // TODO: throw an exception instead? Log something?
    }
  }

  // translation between a list of Agora roles and a FireCloud role
  def toFireCloudRole(agoraRoles:Option[List[String]]) = {
    agoraRoles match {
      case None => "NO ACCESS"
      case Some(r) => {
        r.sorted match {
          case List("Create","Manage","Read","Redact","Write") => "OWNER"
          case List("Read") => "READER"
          case _ => "NO ACCESS" // TODO: throw an exception instead? Log something?
        }
      }
    }
  }
}

class AgoraPermissionActor (requestContext: RequestContext) extends Actor with FireCloudRequestBuilding {

  implicit val system = context.system
  import system.dispatcher

  val log = Logging(system, getClass)

  // TODO: tests

  def receive = {
    // GET requests
    case AgoraPermissionHandler.Get(url: String) =>
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val permissionListFuture: Future[HttpResponse] = pipeline { Get(url) }
      permissionListFuture onComplete {
        case Success(response) =>
          response.status match {
            case StatusCodes.OK =>
              val agoraPermissions = unmarshal[List[AgoraPermission]].apply(response)
              val fireCloudPermissions = agoraPermissions.map(x => x.toFireCloudPermission)
              context.parent ! RequestComplete(OK, fireCloudPermissions)
              context stop self
            case x =>
              context.parent ! RequestComplete(x, response.entity)
              context stop self
          }
        case Failure(e) =>
          context.parent ! RequestComplete(StatusCodes.InternalServerError, e.getMessage)
          context stop self
      }

    case AgoraPermissionHandler.Post(url: String, agoraPermissions: List[AgoraPermission]) =>
      val pipeline = authHeaders(requestContext) ~> sendReceive
      // TODO: allow AGORA to see empty values?
      //or should such things bie dropped here?  What if only one of the requests in  batch is bad?
      //should all be dropped?
      //For now, forward to Agora  and let it catch any error?
      val permissionListFuture: Future[HttpResponse] = pipeline { Post(url.toString,
        HttpClient.createJsonHttpEntity(agoraPermissions.toString()))
      }
      permissionListFuture onComplete {
        case Success(response) =>
          response.status match {
            case StatusCodes.OK =>
              val agoraPermission = unmarshal[AgoraPermission].apply(response)
              val fireCloudPermission = agoraPermission.toFireCloudPermission
              context.parent ! RequestComplete(OK, fireCloudPermission)
              context stop self
            case x =>
              context.parent ! RequestComplete(x, response.entity)
              context stop self
          }
        case Failure(e) =>
          context.parent ! RequestComplete(StatusCodes.InternalServerError, e.getMessage)
          context stop self
      }



    case _ =>
      context.parent ! RequestComplete(StatusCodes.BadRequest)
      context stop self
  }


}


