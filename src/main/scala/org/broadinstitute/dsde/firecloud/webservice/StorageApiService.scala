package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, PerRequestCreator}
import spray.routing._

trait StorageApiService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private final val ApiPrefix = "storage"
  private implicit val executionContext = actorRefFactory.dispatcher

  val storageRoutes: Route =
    pathPrefix(ApiPrefix) {
      // call Google's storage REST API for info about this object
      path(Segment / Rest) { (bucket,obj) => requestContext =>
        val extReq = Get( HttpGoogleServicesDAO.getObjectResourceUrl(bucket, obj.toString) )
        externalHttpPerRequest(requestContext, extReq)
      }
    }
}
