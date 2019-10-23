package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.RequestEntity
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.http.{BodyPart, HttpRequest, HttpResponse, MultipartFormData}

import scala.concurrent.{ExecutionContext, Future}


class HttpCromIAMDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext) extends CromIAMDAO with RestJsonClient with LazyLogging {

  private val workflowRoot = FireCloudConfig.CromIAM.authUrl + "/workflows/v1"

  override def submit(wdlUrl: String, inputs: String, options: Option[String])(implicit userToken: WithAccessToken): Future[String] = {
    val bodyParts = Map(
      "workflowUrl" -> BodyPart(wdlUrl),
      "workflowInputs" -> BodyPart(inputs)
    ) ++ options.map(
      "workflowOptions" -> BodyPart(_)
    )

    val formData = MultipartFormData(bodyParts)
    val response = authedRequestToObject[String](Post(workflowRoot, formData))
    response
  }

  override def status: Future[SubsystemStatus] = {
    Future.successful(SubsystemStatus(ok = true, None))
  }
}
