package org.broadinstitute.dsde.firecloud.model

import java.time.Instant

object ShareLog {
  object ShareType extends Enumeration {
    type EntityType = Value
    val WORKSPACE: ShareType.Value = Value("workspace")
    val GROUP: ShareType.Value = Value("group")
    val METHOD: ShareType.Value = Value("method")
  }
  case class Share(userId: String, sharee: String, shareType: ShareType.Value, timestamp: Option[Instant] = None)
}


