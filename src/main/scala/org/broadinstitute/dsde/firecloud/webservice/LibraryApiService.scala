package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impCurator
import org.broadinstitute.dsde.firecloud.model.{Curator, UserInfo}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, LibraryService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.routing._

import scala.concurrent.ExecutionContext

trait LibraryApiService extends HttpService with FireCloudRequestBuilding
  with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val ec: ExecutionContext = actorRefFactory.dispatcher

  lazy val rawlsCuratorUrl = FireCloudConfig.Rawls.authUrl + "/user/role/curator"

  val libraryServiceConstructor: UserInfo => LibraryService

  val libraryRoutes: Route =
    pathPrefix("schemas") {
      path("library-attributedefinitions-v1") {
        respondWithJSON {
          withResourceFileContents("library/attribute-definitions.json") { jsonContents =>
            complete(OK, jsonContents)
          }
        }
      }
    } ~
    pathPrefix("api") {
      requireUserInfo() { userInfo =>
        pathPrefix("library") {
          path("user" / "role" / "curator") {
            get { requestContext =>
              val pipeline = authHeaders(requestContext) ~> sendReceive
              pipeline {
                Get(rawlsCuratorUrl)
              } map { response =>
                response.status match {
                  case OK => requestContext.complete(OK, Curator(true))
                  case NotFound => requestContext.complete(OK, Curator(false))
                  case _ => requestContext.complete(response) // replay the root exception
                }
              }
            }
          } ~
          pathPrefix(Segment / Segment) { (namespace, name) =>
            path("metadata") {
              put {
                entity(as[String]) { rawAttrsString => requestContext =>
                  perRequest(requestContext,
                    LibraryService.props(libraryServiceConstructor, userInfo),
                    LibraryService.UpdateAttributes(namespace, name, rawAttrsString.toString.parseJson))
                }
              }
            } ~
            path("published") {
              post { requestContext =>
                perRequest(requestContext,
                  LibraryService.props(libraryServiceConstructor, userInfo),
                  LibraryService.SetPublishAttribute(namespace, name, true))
              } ~
              delete { requestContext =>
                perRequest(requestContext,
                  LibraryService.props(libraryServiceConstructor, userInfo),
                  LibraryService.SetPublishAttribute(namespace, name, false))
              }
            }
          }
        }
      }
    }

}
