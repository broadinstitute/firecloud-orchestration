package org.broadinstitute.dsde.firecloud.webservice

import akka.Done
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{ExportEntitiesByTypeActor, FireCloudDirectives, FireCloudRequestBuilding, PerRequestCreator}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.http._
import spray.routing.{HttpService, Route}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success


/**
  * Created by grushton on 6/20/17.
  */
trait ExportEntitiesApiService extends HttpService with PerRequestCreator with FireCloudDirectives with FireCloudRequestBuilding with StandardUserInfoDirectives {

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  def exportEntitiesRoutes: Route =

    path( "api" / "workspaces" / Segment / Segment / "entities" / Segment / "tsv" ) { (workspaceNamespace, workspaceName, entityType) =>
      parameters('attributeNames.?) { attributeNamesString =>
        requireUserInfo() { userInfo =>
          requestContext =>
            val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
            val exportProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, userInfo)
            val exportMessage = ExportEntitiesByTypeActor.ExportEntities(requestContext, workspaceNamespace, workspaceName, entityType, attributeNames)
            val exportActor = actorRefFactory.actorOf(exportProps)
            // Necessary for the actor ask pattern
            implicit val timeout: Timeout = 1.minutes
            lazy val exportFuture = (exportActor ? exportMessage).mapTo[Done]
            onComplete(exportFuture) {
              case Success(result) => complete(StatusCodes.OK)
              case _ => complete(StatusCodes.InternalServerError, "Error generating entity download")
            }.apply(requestContext)
        }
      }
    }
}
