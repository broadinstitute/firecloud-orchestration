package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.DsdeHttpDAO
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.{HttpClientUtilsStandard, StandardUserInfoDirectives}

import scala.concurrent.ExecutionContext

trait StaticNotebooksApiService extends FireCloudDirectives with StandardUserInfoDirectives with DsdeHttpDAO {

  override val http = Http(system)
  override val httpClientUtils = HttpClientUtilsStandard()

  implicit val executionContext: ExecutionContext

  val calhounStaticNotebooksRoot: String = FireCloudConfig.StaticNotebooks.baseUrl
  val calhounStaticNotebooksURL: String = s"$calhounStaticNotebooksRoot/api/convert"

  val staticNotebooksRoutes: Route = {
    path("staticNotebooks" / "convert") {
      requireUserInfo() { userInfo =>
        post {
          requestContext =>
            // call Calhoun and pass its response back to our own caller
            // can't use passthrough() here because that demands a JSON response

            //TODO: ensure mediatype is still honored, but respondWithMediaType was deprecated
            //because it was an anti-pattern

            val extReq = Post(calhounStaticNotebooksURL, requestContext.request.entity)
            executeRequestRaw(userInfo.accessToken)(extReq).flatMap { resp =>
              requestContext.complete(resp)
            }
        }
      }
    }
  }
}
