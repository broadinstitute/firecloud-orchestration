package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MockConsentDAO extends ConsentDAO {


  override def getRestriction(orspId: String): Future[Option[String]] = Future.successful(None)

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))

}
