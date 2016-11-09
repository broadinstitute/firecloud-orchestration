package org.broadinstitute.dsde.firecloud.service

import akka.actor.Props
import org.broadinstitute.dsde.firecloud.core.{ProfileClient, ProfileClientActor}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.routing._

trait NIHSyncService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  override lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    post {
      path("sync_whitelist") { requestContext =>
        perRequest(requestContext, Props(new ProfileClientActor(requestContext)),
          ProfileClient.SyncWhitelist)
      }
    }
}
