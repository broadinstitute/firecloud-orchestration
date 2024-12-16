package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.utils.StreamingPassthrough

trait PassthroughApiService extends Directives with StreamingPassthrough {

  private lazy val agora = FireCloudConfig.Agora.baseUrl
  private lazy val cromiam = FireCloudConfig.CromIAM.baseUrl
  private lazy val rawls = FireCloudConfig.Rawls.baseUrl
  private lazy val sam = FireCloudConfig.Sam.baseUrl

  val passthroughRoutes: Route = concat(
    // Agora
    pathPrefix("api" / "configurations")(streamingPassthrough(s"$agora/api/v1/configurations")),
    pathPrefix("api" / "methods")(streamingPassthrough(s"$agora/api/v1/methods")),
    pathPrefix("ga4gh")(streamingPassthrough(s"$agora/ga4gh")),
    // CromIAM
    pathPrefix("api" / "womtool")(streamingPassthrough(s"$cromiam/api/womtool")),
    // Rawls
    pathPrefix("api" / "inputsOutputs")(streamingPassthrough(s"$rawls/api/methodconfigs/inputsOutputs")),
    pathPrefix("api" / "profile" / "billing")(streamingPassthrough(s"$rawls/api/user/billing")),
    pathPrefix("api" / "template")(streamingPassthrough(s"$rawls/api/methodconfigs/template")),
    // Sam
    pathPrefix("api" / "proxyGroup")(streamingPassthrough(s"$sam/api/google/user/proxyGroup")),
    pathPrefix("register")(streamingPassthrough(s"$sam/register/user")),
    pathPrefix("tos")(streamingPassthrough(s"$sam/tos")),

    // any /api routes not otherwise defined will pass through to Rawls
    pathPrefix("api")(streamingPassthrough(s"$rawls/api"))
  )

}
