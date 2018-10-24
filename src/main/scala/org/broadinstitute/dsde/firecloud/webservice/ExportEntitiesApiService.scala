package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.service.{ExportEntitiesByTypeActor, ExportEntitiesByTypeArguments, FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.routing.{HttpService, Route}

import scala.language.postfixOps

trait ExportEntitiesApiService extends HttpService with FireCloudDirectives with FireCloudRequestBuilding with StandardUserInfoDirectives with LazyLogging {

  val exportEntitiesByTypeConstructor: ExportEntitiesByTypeArguments => ExportEntitiesByTypeActor

  private implicit val executionContext = actorRefFactory.dispatcher

  val exportEntitiesRoutes: Route =

    // Note that this endpoint works in the same way as CookieAuthedApiService tsv download.
    path( "api" / "workspaces" / Segment / Segment / "entities" / Segment / "tsv" ) { (workspaceNamespace, workspaceName, entityType) =>
      parameters('attributeNames.?, 'model.?) { (attributeNamesString, modelString) =>
        requireUserInfo() { userInfo =>
          get {
            requestContext =>
              val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
              val exportArgs = ExportEntitiesByTypeArguments(requestContext, userInfo, workspaceNamespace, workspaceName, entityType, attributeNames, modelString)
              val exportProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, exportArgs)
              actorRefFactory.actorOf(exportProps) ! ExportEntitiesByTypeActor.ExportEntities
          }
        }
      }
    }
}
