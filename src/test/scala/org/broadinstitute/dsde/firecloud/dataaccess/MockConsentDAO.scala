package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.SubsystemStatus

import scala.concurrent.{ExecutionContext, Future}

class MockConsentDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext) extends ConsentDAO {

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(true))

}
