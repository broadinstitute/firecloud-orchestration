package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.utils.StreamingPassthrough

trait PassthroughApiService extends Directives with StreamingPassthrough {

  private lazy val agora = FireCloudConfig.Agora.baseUrl
  private lazy val rawls = FireCloudConfig.Rawls.baseUrl

  val passthroughRoutes: Route = concat(
    pathPrefix("ga4gh")(streamingPassthrough(s"$agora/ga4gh")),
    pathPrefix("api" / "billing")(streamingPassthrough(s"$rawls/api/billing")),
    pathPrefix("api" / "notifications")(streamingPassthrough(s"$rawls/api/notifications")),
    pathPrefix("api" / "workspaces")(streamingPassthrough(s"$rawls/api/workspaces"))
  )

}
