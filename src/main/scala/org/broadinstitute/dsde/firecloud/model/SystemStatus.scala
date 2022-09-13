package org.broadinstitute.dsde.firecloud.model

/**
  * Created by anichols on 4/7/17.
  */
case class ThurloeStatus(status: String, error: Option[String])
case class DropwizardHealth(healthy: Boolean, message: Option[String])
