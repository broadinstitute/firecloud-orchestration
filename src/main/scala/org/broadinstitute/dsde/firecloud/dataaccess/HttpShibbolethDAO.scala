package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient

import scala.concurrent.{ExecutionContext, Future}

class HttpShibbolethDAO(implicit val system: ActorSystem, implicit val materializer: Materializer, implicit val executionContext: ExecutionContext)
  extends ShibbolethDAO with RestJsonClient with SprayJsonSupport {

  override def getPublicKey(): Future[String] = {
    val publicKeyUrl = FireCloudConfig.Shibboleth.publicKeyUrl

    unAuthedRequestToObject[String](Get(publicKeyUrl))
  }
}
