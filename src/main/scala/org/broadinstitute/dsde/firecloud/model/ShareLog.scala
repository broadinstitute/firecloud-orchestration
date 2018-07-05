package org.broadinstitute.dsde.firecloud.model

import java.time.Instant

object ShareLog {
  final val WORKSPACE = "workspace"
  final val GROUP = "group"
  final val METHOD = "method"
  case class Share(userId: String, sharee: String, shareType: String, timestamp: Instant)
}


