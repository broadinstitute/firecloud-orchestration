package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.Actor
import org.slf4j.LoggerFactory
import spray.routing._


class StatusServiceActor extends Actor with StatusService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait StatusService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private final val ApiPrefix = "status"
  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    pathPrefix(ApiPrefix) {
      path("ping") {
        complete {
          dateFormat.format(new Date())
        }
      }
    }
}
