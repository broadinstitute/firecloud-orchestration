package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{HttpMethods, Uri}
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives

import scala.concurrent.ExecutionContext

trait Ga4ghApiService extends FireCloudDirectives {

  implicit val executionContext: ExecutionContext

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
              parameterSeq { params =>
                val targetUri = Uri(s"$agoraGA4GH/tools")
                val uri = if (params.isEmpty) {
                  targetUri
                } else {
                  targetUri.withQuery(Query(params.toMap))
                }
                passthrough(uri, HttpMethods.GET)
              }
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
              val targetUri = Uri(s"$agoraGA4GH/tools/$id/versions/$versionId/dockerfile")
              passthrough(targetUri, HttpMethods.GET)
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
              val targetUri = Uri(s"$agoraGA4GH/tools/$id/versions/$versionId/$descriptorType/descriptor/$relativePath")
              passthrough(targetUri, HttpMethods.GET)
            } ~
            path("tools" / Segment / "versions" / Segment / Segment / "tests") { (id, versionId, descriptorType) =>
              val targetUri = Uri(s"$agoraGA4GH/tools/$id/versions/$versionId/$descriptorType/tests")
              passthrough(targetUri, HttpMethods.GET)
            }
        }
      }
    }

}
