package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.routing.{HttpService, Route}

import scala.language.postfixOps


/**
  * Created by grushton on 6/20/17.
  */
trait ExportEntitiesApiService extends HttpService with FireCloudDirectives with FireCloudRequestBuilding with StandardUserInfoDirectives with LazyLogging {

  val exportEntitiesByTypeConstructor: ExportEntitiesByTypeArguments => ExportEntitiesByTypeActor

  private implicit val executionContext = actorRefFactory.dispatcher

  val exportEntitiesRoutes: Route =

    // Note that this endpoint works in the same way as CookieAuthedApiService tsv download.
    path( "api" / "workspaces" / Segment / Segment / "entities" / Segment / "tsv" ) { (workspaceNamespace, workspaceName, entityType) =>
      parameters('attributeNames.?) { attributeNamesString =>
        requireUserInfo() { userInfo =>
          get {
            requestContext =>
              val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
              val exportProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, ExportEntitiesByTypeArguments(userInfo, workspaceNamespace, workspaceName, entityType, attributeNames))
              val exportMessage = ExportEntitiesByTypeActor.ExportEntities(requestContext)
              val exportActor = actorRefFactory.actorOf(exportProps)
              exportActor ! exportMessage
          }
        }
      }
    }
}
