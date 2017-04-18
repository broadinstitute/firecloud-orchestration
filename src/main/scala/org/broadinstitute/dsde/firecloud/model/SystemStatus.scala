package org.broadinstitute.dsde.firecloud.model

import scala.concurrent.Future

/**
  * Created by anichols on 4/7/17.
  */
case class SystemStatus(ok: Boolean, systems: Map[String, SubsystemStatus])

case class SubsystemStatus(ok: Boolean, messages: Option[Array[String]])

case class AgoraStatus(status: String, message: Array[String])
case class ThurloeStatus(status: String, error: Option[String])
case class RawlsStatus(version: Option[String])

trait ReportsSubsystemStatus {
  def status: Future[SubsystemStatus]
}