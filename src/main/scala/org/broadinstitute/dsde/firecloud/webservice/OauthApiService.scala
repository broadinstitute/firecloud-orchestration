package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impCurator
import org.broadinstitute.dsde.firecloud.model.{Curator, UserInfo}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, LibraryService, OAuthService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.client.pipelining._
import spray.http.StatusCodes
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.JsonParser.ParsingException
import spray.json._
import spray.routing._

import scala.concurrent.ExecutionContext

trait OauthApiService extends HttpService with FireCloudRequestBuilding
  with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val ec: ExecutionContext = actorRefFactory.dispatcher

  val oauthServiceConstructor: () => OAuthService

  def completeWithMissingKey(rc: RequestContext, key: String) = rc.complete(
    StatusCodes.BadRequest,
    Map("error" -> Map("summary" -> s"Missing required key '$key'"))
  )

  def completeWithBadValue(rc: RequestContext, key: String, message: String) = rc.complete(
    StatusCodes.BadRequest,
    Map("error" -> Map("summary" -> s"Invalid value for '$key'", "detail" -> message))
  )

  case class HandleOauthCodeParams(code: String, redirectUri: String)
  implicit val impHandleOathCodeParams = jsonFormat2(HandleOauthCodeParams)

  val oauthRoutes: Route =
    path("handle-oauth-code") {
      post { requestContext =>
        try {
          val params = requestContext.request.entity.data.asString.parseJson.asJsObject
          params.fields.get("code") match {
            case None => completeWithMissingKey(requestContext, "code")
            case Some(jsvCode) =>
              try {
                val code = jsvCode.convertTo[String]
                params.fields.get("redirectUri") match {
                  case None => completeWithMissingKey(requestContext, "redirectUri")
                  case Some(jsvRedirectUri) =>
                    try {
                      val redirectUri = jsvRedirectUri.convertTo[String]
                      perRequest(requestContext,
                        OAuthService.props(oauthServiceConstructor),
                        OAuthService.HandleOauthCode(code, redirectUri)
                      )
                    } catch {
                      case e: DeserializationException =>
                        completeWithBadValue(requestContext, "code", e.msg)
                    }
                }
              } catch {
                case e: DeserializationException =>
                  completeWithBadValue(requestContext, "code", e.msg)
              }
          }
        } catch {
          case e: ParsingException =>
            requestContext.complete(
              StatusCodes.BadRequest,
              Map("error" -> Map("summary" -> e.summary))
            )
        }
      }
    } ~
      path("handle-oauth-code-v2") {
        post {
          entity(as[HandleOauthCodeParams]) { params => requestContext =>
            perRequest(requestContext,
              OAuthService.props(oauthServiceConstructor),
              OAuthService.HandleOauthCode(params.code, params.redirectUri)
            )
          }
        }
      } ~
    path("api" / "refresh-token-status") {
      get {
        requireUserInfo() { userInfo => rc =>
          perRequest(rc,
            OAuthService.props(oauthServiceConstructor),
            OAuthService.GetRefreshTokenStatus(userInfo)
          )
        }
      }
    }
}
