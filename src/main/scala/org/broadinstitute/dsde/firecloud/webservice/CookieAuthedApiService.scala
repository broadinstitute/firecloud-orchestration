package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service._
import spray.http._
import spray.routing._

import scala.language.postfixOps

/**
 * Created by dvoet on 11/16/16.
 */
trait CookieAuthedApiService extends HttpService with FireCloudDirectives with FireCloudRequestBuilding with LazyLogging {

  val exportEntitiesByTypeConstructor: ExportEntitiesByTypeArguments => ExportEntitiesByTypeActor

  private implicit val executionContext = actorRefFactory.dispatcher

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
            val exportMessage = ExportEntitiesByTypeActor.ExportEntities
            val exportActor = actorRefFactory.actorOf(exportProps)
            exportActor ! exportMessage
          }
        }
    } ~
    path( "cookie-authed" / "download" / "b" / Segment / "o" / RestPath ) { (bucket, obj) =>
      cookie("FCtoken") { tokenCookie =>
        mapRequest(r => addCredentials(OAuth2BearerToken(tokenCookie.content)).apply(r)) { requestContext =>
          HttpGoogleServicesDAO.getDownload(requestContext, bucket, obj.toString, tokenCookie.content)
        }
      }
    }
}
