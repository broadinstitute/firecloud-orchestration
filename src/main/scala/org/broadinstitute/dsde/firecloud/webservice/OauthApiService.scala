package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, OAuthService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.http.StatusCodes
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
          val jsvCode = params.fields.get("code")
          val jsvRedirectUri = params.fields.get("redirectUri")
          (jsvCode, jsvRedirectUri) match {
            case (Some(code:JsString), Some(redirectUri:JsString)) =>
              perRequest(requestContext,
                OAuthService.props(oauthServiceConstructor),
                OAuthService.HandleOauthCode(code.value, redirectUri.value)
              )
            case _ =>
              requestContext.complete(
                StatusCodes.BadRequest,
                Map("error" ->
                  Map("summary" -> s"code (string) and redirectUri (string) required",
                    "detail" -> s"got code:$jsvCode, redirectUri:$jsvRedirectUri"
                  ))
              )
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
