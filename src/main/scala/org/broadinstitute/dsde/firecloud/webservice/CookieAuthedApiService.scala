package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service._
import spray.http._
import spray.routing._

import scala.language.postfixOps

/**
 * Created by dvoet on 11/16/16.
 */
trait CookieAuthedApiService extends HttpService with PerRequestCreator with FireCloudDirectives with FireCloudRequestBuilding with LazyLogging {

  val exportEntitiesByTypeConstructor: ExportEntitiesByTypeArguments => ExportEntitiesByTypeActor

  private implicit val executionContext = actorRefFactory.dispatcher

  val storageServiceConstructor: UserInfo => StorageService

  private def dummyUserInfo(tokenStr: String) = UserInfo("dummy", OAuth2BearerToken(tokenStr), -1, "dummy")

  val cookieAuthedRoutes: Route =
    // download "proxies" for TSV files
    // Note that these endpoints work in the same way as ExportEntitiesApiService tsv download.
    path( "cookie-authed" / "workspaces" / Segment / Segment/ "entities" / Segment / "tsv" ) { (workspaceNamespace, workspaceName, entityType) =>
      // this endpoint allows an arbitrary number of attribute names in the POST body (GAWB-1435)
      // but the URL cannot be saved for later use (firecloud-app#80)
      post {
        formFields('FCtoken, 'attributeNames.?) { (tokenValue, attributeNamesString) => requestContext =>
          val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
          val userInfo = dummyUserInfo(tokenValue)
          val exportArgs = ExportEntitiesByTypeArguments(requestContext, userInfo, workspaceNamespace, workspaceName, entityType, attributeNames)
          val exportProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, exportArgs)
          actorRefFactory.actorOf(exportProps) ! ExportEntitiesByTypeActor.ExportEntities
        }
      } ~
      // this endpoint allows saving the URL for later use (firecloud-app#80)
      // but it's possible to exceed the maximum URI length by specifying too many attributes (GAWB-1435)
      get {
        cookie("FCtoken") { tokenCookie =>
          parameters('attributeNames.?) { attributeNamesString =>
            requestContext =>
              val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
              val userInfo = dummyUserInfo(tokenCookie.content)
              val exportArgs = ExportEntitiesByTypeArguments(requestContext, userInfo, workspaceNamespace, workspaceName, entityType, attributeNames)
              val exportProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, exportArgs)
              actorRefFactory.actorOf(exportProps) ! ExportEntitiesByTypeActor.ExportEntities
            }
        }
      }
    } ~
    path( "cookie-authed" / "download" / "b" / Segment / "o" / RestPath ) { (bucket, obj) =>
      get {
        cookie("FCtoken") { tokenCookie => requestContext =>
          val userInfo = dummyUserInfo(tokenCookie.content)

          perRequest(requestContext,
            StorageService.props(storageServiceConstructor, userInfo),
            StorageService.GetDownload(bucket, obj.toString))
        }
      }
    }


}
