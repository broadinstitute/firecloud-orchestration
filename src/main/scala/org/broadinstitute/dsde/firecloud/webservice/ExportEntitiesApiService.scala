package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{ExportEntitiesByTypeActor, FireCloudDirectives, FireCloudRequestBuilding, PerRequestCreator}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.routing.{HttpService, Route}

import scala.language.postfixOps


/**
  * Created by grushton on 6/20/17.
  */
trait ExportEntitiesApiService extends HttpService with PerRequestCreator with FireCloudDirectives with FireCloudRequestBuilding with StandardUserInfoDirectives {

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  val exportEntitiesRoutes: Route =

    path( "api" / "workspaces" / Segment / Segment / "entities" / Segment / "tsv" ) { (workspaceNamespace, workspaceName, entityType) =>
      parameters('attributeNames.?) { attributeNamesString =>
        requireUserInfo() { userInfo =>
          get {
            requestContext =>
              val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
              val exportProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, userInfo)
              val exportMessage = ExportEntitiesByTypeActor.ExportEntities(requestContext, workspaceNamespace, workspaceName, entityType, attributeNames)
              val exportActor = actorRefFactory.actorOf(exportProps)
              exportActor ! exportMessage
          }
        }
      }
    }
}
