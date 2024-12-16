package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.utils.StreamingPassthrough

trait PassthroughApiService extends Directives with StreamingPassthrough {

  private lazy val agora = FireCloudConfig.Agora.baseUrl
  private lazy val rawls = FireCloudConfig.Rawls.baseUrl
  private lazy val sam = FireCloudConfig.Sam.baseUrl

  val passthroughRoutes: Route = concat(
    pathPrefix("api" / "billing")(streamingPassthrough(s"$rawls/api/billing")),
    pathPrefix("api" / "configurations")(streamingPassthrough(s"$agora/api/v1/configurations")),
    pathPrefix("api" / "methods")(streamingPassthrough(s"$agora/api/v1/methods")),
    pathPrefix("api" / "notifications")(streamingPassthrough(s"$rawls/api/notifications")),
    pathPrefix("api" / "servicePerimeters")(streamingPassthrough(s"$rawls/api/servicePerimeters")),
    pathPrefix("api" / "workspaces")(streamingPassthrough(s"$rawls/api/workspaces")),
    pathPrefix("ga4gh")(streamingPassthrough(s"$agora/ga4gh")),
    pathPrefix("register" / "user" / "v1" / "termsofservice")(
      streamingPassthrough(s"$sam/register/user/v1/termsofservice")
    ),
    pathPrefix("register" / "user" / "v2" / "self" / "termsOfServiceDetails")(
      streamingPassthrough(s"$sam/register/user/v2/self/termsOfServiceDetails")
    ),
    // TODO: /tos is deprecated in Sam but not in Orch?
    pathPrefix("tos")(streamingPassthrough(s"$sam/tos"))
  )

}
