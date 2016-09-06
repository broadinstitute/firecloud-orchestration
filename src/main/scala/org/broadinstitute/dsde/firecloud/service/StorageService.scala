package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import spray.routing._

class StorageServiceActor extends Actor with StorageService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait StorageService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private final val ApiPrefix = "storage"
  private implicit val executionContext = actorRefFactory.dispatcher

  val routes: Route =
    pathPrefix(ApiPrefix) {
      // call Google's storage REST API for info about this object
      path(Segment / Rest) { (bucket,obj) => requestContext =>
        val extReq = Get( HttpGoogleServicesDAO.getObjectResourceUrl(bucket, obj.toString) )
        externalHttpPerRequest(requestContext, extReq)
      }
    }
}
