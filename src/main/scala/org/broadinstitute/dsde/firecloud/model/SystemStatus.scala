package org.broadinstitute.dsde.firecloud.model

/**
  * Created by anichols on 4/7/17.
  */
case class SystemStatus(ok: Boolean, systems: Map[String, SubsystemStatus])

case class SubsystemStatus(ok: Boolean, messages: Option[List[String]] = None)

case class AgoraStatus(status: String, message: List[String])
case class ThurloeStatus(status: String, error: Option[String])

case class RawlsSubsystemStatus(ok: Boolean, messages: List[String])
case class RawlsStatus(ok: Boolean, systems: Map[String, RawlsSubsystemStatus])