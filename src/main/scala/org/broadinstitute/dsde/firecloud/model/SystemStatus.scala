package org.broadinstitute.dsde.firecloud.model

/**
  * Created by anichols on 4/7/17.
  */
case class SystemStatus(ok: Boolean, systems: Map[String, SubsystemStatus])

case class SubsystemStatus(ok: Boolean, messages: Option[List[String]] = None)

case class AgoraStatus(status: String, message: List[String])
case class ThurloeStatus(status: String, error: Option[String])
case class OntologyStatus(
  deadlocks: Map[String, String],
  `elastic-search`: Map[String, String],
  `google-cloud-storage`: Map[String, String]
)
case class ConsentStatus(
  deadlocks: Map[String, String],
  `elastic-search`: Map[String, String],
  `google-cloud-storage`: Map[String, String],
  mongodb: Map[String, String],
  mysql: Map[String, String]
)


