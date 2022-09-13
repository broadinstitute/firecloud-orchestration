package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.EntityAccessControl
import org.broadinstitute.dsde.rawls.model.AccessEntry

/**
  * Created by davidan on 7/5/17.
  */
case class PermissionReport (
  workspaceACL: Map[String, AccessEntry],
  referencedMethods: Seq[EntityAccessControl]
)

case class PermissionReportRequest (
  users: Option[Seq[String]],
  configs: Option[Seq[OrchMethodConfigurationName]]
)
