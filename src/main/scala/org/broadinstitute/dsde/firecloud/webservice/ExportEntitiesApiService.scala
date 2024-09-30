package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.{Directives, Route}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.service.{ExportEntitiesByTypeActor, ExportEntitiesByTypeArguments, FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

trait ExportEntitiesApiService extends Directives with RequestBuilding with StandardUserInfoDirectives with LazyLogging {

  val exportEntitiesByTypeConstructor: ExportEntitiesByTypeArguments => ExportEntitiesByTypeActor

  implicit val executionContext: ExecutionContext

  val exportEntitiesRoutes: Route =

    // Note that this endpoint works in the same way as CookieAuthedApiService tsv download.
    pathPrefix( "api" / "workspaces" / Segment / Segment / "entities" / Segment / "tsv" ) { (workspaceNamespace, workspaceName, entityType) =>
      parameters(Symbol("attributeNames").?, Symbol("model").?) { (attributeNamesString, modelString) =>
        requireUserInfo() { userInfo =>
          val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
          val exportArgs = ExportEntitiesByTypeArguments(userInfo, workspaceNamespace, workspaceName, entityType, attributeNames, modelString)
          pathEnd {
            get {
              complete { exportEntitiesByTypeConstructor(exportArgs).ExportEntities }
            }
          } ~
          path("save") {
            post {
             complete {
               exportEntitiesByTypeConstructor(exportArgs).streamEntitiesToWorkspaceBucket() map { gcsPath =>
                 RequestComplete(OK, s"gs://${gcsPath.bucketName}/${gcsPath.objectName.value}")
               }
             }
            }
          }
        }
      }
    }
}
