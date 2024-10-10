package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.{Directives, Route}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.lang3.StringUtils
import org.broadinstitute.dsde.firecloud.service.PerRequest.{RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.service.{ExportEntitiesByTypeActor, ExportEntitiesByTypeArguments}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

trait ExportEntitiesApiService extends Directives with RequestBuilding with StandardUserInfoDirectives with LazyLogging with SprayJsonSupport {

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
    } ~
  // *******************************************************************************************************************
  // POC of file-matching for AJ-2025
  // *******************************************************************************************************************
  // TODO: add swagger definition
  path( "api" / "workspaces" / Segment / Segment / "entities" / Segment / "tsv" / "frombucket") { (workspaceNamespace, workspaceName, entityType) =>
    requireUserInfo() { userInfo =>
      post {
        import ExportEntitiesByTypeActor._
        entity(as[FileMatchingOptions]) { matchingOptions =>
          val attributeNames = None
          val model = None
          val exportArgs = ExportEntitiesByTypeArguments(userInfo, workspaceNamespace, workspaceName, entityType, attributeNames, model)

          complete {
            exportEntitiesByTypeConstructor(exportArgs).matchBucketFiles(matchingOptions) map { pairs =>
              // download the TSV as an attachment:
              // RequestCompleteWithHeaders((OK, pairs),
              //  `Content-Type`.apply(ContentType.apply(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)),
              //  `Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "filematching.tsv"))
              // )

              // for easy debugging: output the TSV as text
              RequestComplete(OK, pairs)
            }
          }
        }
      }
    }
  }
  // *******************************************************************************************************************
  // POC of file-matching for AJ-2025
  // *******************************************************************************************************************

}
