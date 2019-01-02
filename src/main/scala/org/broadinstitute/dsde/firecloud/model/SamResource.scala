package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName

object SamResource {

  case class ResourceId(value: String)
  case class AccessPolicyName(value: String)
  case class UserPolicy(resourceId: ResourceId, public: Boolean, accessPolicyName: AccessPolicyName, missingAuthDomainGroups: Seq[WorkbenchGroupName], authDomainGroups: Seq[WorkbenchGroupName])
}
