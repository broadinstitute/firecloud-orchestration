package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import better.files.File
import akka.util.Timeout
import better.files.File
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
            val filename = entityType + ".tsv"
            val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
            val exportProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, userInfo)
            val exportMessage = ExportEntitiesByTypeActor.ExportEntities(requestContext, workspaceNamespace, workspaceName, entityType, attributeNames)
            val exportActor = actorRefFactory.actorOf(exportProps)
            // Necessary for the actor ask pattern
            implicit val timeout: Timeout = 1.minutes
            lazy val fileFuture = (exportActor ? exportMessage).mapTo[File]
            onComplete(fileFuture) {
              case Success(file) =>
                val httpEntity = file.contentType match {
                  case Some(cType) if cType.contains("text") => HttpEntity(ContentTypes.`text/plain`, file.contentAsString)
                  case _ => HttpEntity(ContentTypes.`application/octet-stream`, file.loadBytes)
                }
                complete(HttpResponse(
                  status = StatusCodes.OK,
                  entity = httpEntity,
                  headers = List(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> file.name)))))
              case _ =>
                complete(StatusCodes.InternalServerError, "Error generating entity download")
            }.apply(requestContext)
        }
      }
    }
}
