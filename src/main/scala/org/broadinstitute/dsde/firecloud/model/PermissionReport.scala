package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.MethodRepository.EntityAccessControl
import org.broadinstitute.dsde.rawls.model.AccessEntry

/**
  * Created by davidan on 7/5/17.
  */
case class PermissionReport (
  workspaceACL: Map[String, AccessEntry],
  referencedMethods: Seq[EntityAccessControl]
)
