package org.broadinstitute.dsde.firecloud.dataaccess

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem

import scala.concurrent.Future

/**
  * Created by anichols on 4/21/17.
  */
trait ReportsSubsystemStatus extends SprayJsonSupport {

  def status: Future[SubsystemStatus]

  def serviceName: Subsystem
}
