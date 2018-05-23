package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
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

  val cookieAuthedRoutes: Route =

    // download "proxy" for TSV files
    // Note that this endpoint works in the same way as ExportEntitiesApiService tsv download.
    path( "cookie-authed" / "workspaces" / Segment / Segment/ "entities" / Segment / "tsv" ) {
      (workspaceNamespace, workspaceName, entityType) =>
        formFields('FCtoken, 'attributeNames.?) { (tokenValue, attributeNamesString) =>
          post { requestContext =>
            val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
            val userInfo = UserInfo("dummy", OAuth2BearerToken(tokenValue), -1, "dummy")
            val exportArgs = ExportEntitiesByTypeArguments(requestContext, userInfo, workspaceNamespace, workspaceName, entityType, attributeNames)
            val exportProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, exportArgs)
            actorRefFactory.actorOf(exportProps) ! ExportEntitiesByTypeActor.ExportEntities
          }
        }
    } ~
    path( "cookie-authed" / "download" / "b" / Segment / "o" / RestPath ) { (bucket, obj) =>
      cookie("FCtoken") { tokenCookie => requestContext =>
        // TODO: use a different WithAccessToken that doesn't require mocking out unknown values
        val userInfoFromCookie = UserInfo(tokenCookie.content, "")

        perRequest(requestContext,
          StorageService.props(storageServiceConstructor, userInfoFromCookie),
          StorageService.GetObjectStats(bucket, obj.toString))
      }
    }
}
