package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.{AgoraEntityType, AgoraPermission, EntityAccessControlAgora, Method}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.webservice.MethodsApiServiceUrls
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport.{StatusCheckResponseFormat, SubsystemStatusFormat}
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class HttpAgoraDAO(config: FireCloudConfig.Agora.type)(implicit val system: ActorSystem, implicit val materializer: Materializer, implicit val executionContext: ExecutionContext)
  extends AgoraDAO with SprayJsonSupport with RestJsonClient with MethodsApiServiceUrls {

  private def getNamespaceUrl(ns: String, entity: String): String = {
    s"${config.authUrl}/$entity/$ns/permissions"
  }

  private def getMultiEntityPermissionUrl(entityType: AgoraEntityType.Value) = {
    s"${config.authUrl}/${AgoraEntityType.toPath(entityType)}/permissions"
  }

  override def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] =
    authedRequestToObject[List[AgoraPermission]]( Get(getNamespaceUrl(ns, entity)) )

  override def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] =
    authedRequestToObject[List[AgoraPermission]]( Post(getNamespaceUrl(ns, entity), perms) )

  override def getMultiEntityPermissions(entityType: AgoraEntityType.Value, entities: List[Method])(implicit userInfo: UserInfo): Future[List[EntityAccessControlAgora]] = {
    authedRequestToObject[List[EntityAccessControlAgora]]( Post(getMultiEntityPermissionUrl(entityType), entities) )
  }

  override def batchCreatePermissions(inputs: List[EntityAccessControlAgora])(implicit userInfo: UserInfo): Future[List[EntityAccessControlAgora]] = {
    authedRequestToObject[List[EntityAccessControlAgora]](Put(remoteMultiPermissionsUrl, inputs))
  }

  override def getPermission(url: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    authedRequestToObject[List[AgoraPermission]](Get(url))
  }

  override def createPermission(url: String,  agoraPermissions: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    authedRequestToObject[List[AgoraPermission]](Post(url, agoraPermissions))
  }

  override def status: Future[SubsystemStatus] = {
    val agoraStatusCheck = unAuthedRequestToObject[StatusCheckResponse](Get(Uri(config.baseUrl).withPath(Uri.Path("/status"))))

    agoraStatusCheck map { agoraStatus =>
      if (agoraStatus.ok)
        SubsystemStatus(ok = true, None)
      else
        SubsystemStatus(ok = false, Some(agoraStatus.systems.map{
          case (k:Subsystem, v:SubsystemStatus) => s"""$k : ${SubsystemStatusFormat.write(v).compactPrint}"""
          case x => x.toString
        }.toList))
    } recover {
      case fcee:FireCloudExceptionWithErrorReport =>
        // attempt to make the underlying message prettier
        val parseTry = Try(fcee.errorReport.message.parseJson.compactPrint.replace("\"", "'")).toOption
        val msg = parseTry.getOrElse(fcee.errorReport.message)
        SubsystemStatus(ok = false, Some(List(msg)))
      case e:Exception =>
        SubsystemStatus(ok = false, Some(List(e.getMessage)))
    }
  }

}
