package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives

import scala.concurrent.duration._

/**
  * This trait exposes `authorize` and `token` APIs needed to support client OAuth2 flows.
  *
  * We implement these routes on the backend (as opposed to clients calling the oauth endpoints
  * directly) to be able to support Google and B2C authentication in parallel using a consistent
  * client library.
  *
  * See document for more info:
  * https://docs.google.com/document/d/1fnfLSbHWMKi8F__zJm-p7C2URlXIEKzB0-bgyrf-qBg/edit#
  */
trait Oauth2ApiService extends FireCloudDirectives {
  private lazy val authorizationUri = Uri(FireCloudConfig.OAuth2.authorizationEndpoint)
  private lazy val tokenUri = Uri(FireCloudConfig.OAuth2.tokenEndpoint)

  val oauth2Routes: Route = {
    pathPrefix("oauth2") {
      // The /authorize endpoint simply redirects to the underlying oauth2 endpoint
      // with the same querystring parameters.
      path("authorize") {
        get {
          parameterMap { params =>
            val newUri = authorizationUri.withQuery(Uri.Query(params))
            redirect(newUri, StatusCodes.Found)
          }
        }
      } ~
      // The /token endpoint actually proxies the request to the underlying token
      // endpoint, injecting the client_secret value to the form data.
      // We inject the client_secret on the backend to avoid exposing it in the browser
      // (a limitation of the Google OAuth2 implementation).
        path("token") {
          extractRequest { request =>
            post {
              complete {
                // Initialize the connection to the token endpoint
                val flow = Http()
                  .connectionTo(tokenUri.authority.host.address())
                  .https()

                // 1. filter out headers not needed for the backend server
                val newHeaders = request.headers.filterNot(h => headersToFilter(h.lowercaseName()))
                // 2. tack on client_secret to form data
                val newEntity = request.entity.transformDataBytes(Flow.fromFunction { requestEntity =>
                  requestEntity ++
                    FireCloudConfig.OAuth2.clientSecret
                      .map(s => ByteString.fromString(s"&client_secret=$s"))
                      .getOrElse(ByteString.empty)
                })
                // 3. build a new HttpRequest
                val newRequest = request.withUri(tokenUri).withHeaders(newHeaders).withEntity(newEntity)

                // Proxy the new request
                Source
                  .single(newRequest)
                  .via(flow)
                  .runWith(Sink.head)
                  .flatMap(_.toStrict(5.seconds))
              }
            }
          }
        }
    }
  }

  private val headersToFilter = Set(
    "Timeout-Access"
  ).map(_.toLowerCase)
}
