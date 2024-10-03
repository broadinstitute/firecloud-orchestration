package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.{Directives, Route}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.lang3.StringUtils
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.service.{ExportEntitiesByTypeActor, ExportEntitiesByTypeArguments}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

trait ExportEntitiesApiService extends Directives with RequestBuilding with StandardUserInfoDirectives with LazyLogging {

  val exportEntitiesByTypeConstructor: ExportEntitiesByTypeArguments => ExportEntitiesByTypeActor

  implicit val executionContext: ExecutionContext

  val exportEntitiesRoutes: Route =

    // Note that this endpoint works in the same way as CookieAuthedApiService tsv download.
    path( "api" / "workspaces" / Segment / Segment / "entities" / Segment / "tsv" ) { (workspaceNamespace, workspaceName, entityType) =>
      requireUserInfo() { userInfo =>
        get {
          parameters(Symbol("attributeNames").?, Symbol("model").?) { (attributeNamesString, modelString) =>
            val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
            val exportArgs = ExportEntitiesByTypeArguments(userInfo, workspaceNamespace, workspaceName, entityType, attributeNames, modelString)
            complete {
              exportEntitiesByTypeConstructor(exportArgs).ExportEntities
            }
          }
        } ~
        post {
          formFields(Symbol("attributeNames").?, Symbol("model").?) { (attributeNamesString, modelString) =>
            val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
            val model = if (modelString.nonEmpty && StringUtils.isBlank(modelString.get)) None else modelString
            val exportArgs = ExportEntitiesByTypeArguments(userInfo, workspaceNamespace, workspaceName, entityType, attributeNames, model)
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
