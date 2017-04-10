package org.broadinstitute.dsde.firecloud.model

/**
  * Created by anichols on 4/7/17.
  */
case class SystemStatus(message: String)

case class AgoraStatus(status: String, message: Array[String])
case class ThurloeStatus(status: String, error: Option[String])
