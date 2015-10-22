package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import spray.routing._

class StorageServiceActor extends Actor with StorageService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait StorageService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private final val ApiPrefix = "storage"
  private implicit val executionContext = actorRefFactory.dispatcher

  val gcsStatUrl = "https://www.googleapis.com/storage/v1/b/%s/o/%s"

  val routes: Route =
    pathPrefix(ApiPrefix) {
      // call Google's storage REST API for info about this object
      path(Segment / Segment) { (bucket,obj) => requestContext =>
        val extReq = Get(gcsStatUrl.format(bucket,obj))
        externalHttpPerRequest(requestContext, extReq)
      }
    }
}
