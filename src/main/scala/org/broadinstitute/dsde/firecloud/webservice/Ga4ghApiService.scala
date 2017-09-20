package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import spray.http.{HttpMethods, Uri}
import spray.routing._

import scala.concurrent.ExecutionContext

trait Ga4ghApiService extends HttpService with FireCloudDirectives {

  private implicit val ec: ExecutionContext = actorRefFactory.dispatcher

  private val agora = FireCloudConfig.Agora.baseUrl

  val ga4ghRoutes: Route =
    pathPrefix("ga4gh") {
      pathPrefix("tools") {
        path (Segment / "versions" / Segment / Segment / "descriptor") { (id, versionId, descriptorType) =>
          get {
            val targetUri = Uri(s"$agora/ga4gh/tools/$id/versions/$versionId/$descriptorType/descriptor")
            passthrough(targetUri, HttpMethods.GET)
          }
        }
      }
    }

}
