package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import spray.http.{HttpMethods, Uri}
import spray.routing._

import scala.concurrent.ExecutionContext

trait Ga4ghApiService extends HttpService with FireCloudDirectives {

  private implicit val ec: ExecutionContext = actorRefFactory.dispatcher

  private val agoraGA4GH = s"${FireCloudConfig.Agora.baseUrl}/ga4gh/v1"

  val ga4ghRoutes: Route =
    pathPrefix("ga4gh") {
      pathPrefix("v1") {
        get {
          path("metadata") {
            val targetUri = Uri(s"$agoraGA4GH/metadata")
            passthrough(targetUri, HttpMethods.GET)
          } ~
          path("tool-classes") {
            val targetUri = Uri(s"$agoraGA4GH/tool-classes")
            passthrough(targetUri, HttpMethods.GET)
          } ~
          path("tools") {
            val targetUri = Uri(s"$agoraGA4GH/tools")
            passthrough(targetUri, HttpMethods.GET)
          } ~
          path("tools" / Segment) { (id) =>
            val targetUri = Uri(s"$agoraGA4GH/tools/$id")
            passthrough(targetUri, HttpMethods.GET)
          } ~
          path("tools" / Segment / "versions") { (id) =>
            val targetUri = Uri(s"$agoraGA4GH/tools/$id/versions")
            passthrough(targetUri, HttpMethods.GET)
          } ~
          path("tools" / Segment / "versions" / Segment / "dockerfile") { (id, versionId) =>
            complete(spray.http.StatusCodes.NotImplemented)
          } ~
          path("tools" / Segment / "versions" / Segment) { (id, versionId) =>
            val targetUri = Uri(s"$agoraGA4GH/tools/$id/versions/$versionId")
            passthrough(targetUri, HttpMethods.GET)
          } ~
          path("tools" / Segment / "versions" / Segment / Segment / "descriptor") { (id, versionId, descriptorType) =>
            val targetUri = Uri(s"$agoraGA4GH/tools/$id/versions/$versionId/$descriptorType/descriptor")
            passthrough(targetUri, HttpMethods.GET)
          } ~
          path("tools" / Segment / "versions" / Segment / Segment / "descriptor" / Segment) { (id, versionId, descriptorType, relativePath) =>
            complete(spray.http.StatusCodes.NotImplemented)
          } ~
          path("tools" / Segment / "versions" / Segment / Segment / "tests") { (id, versionId, descriptorType) =>
            complete(spray.http.StatusCodes.NotImplemented)
          }
        }
      }
    }

}
