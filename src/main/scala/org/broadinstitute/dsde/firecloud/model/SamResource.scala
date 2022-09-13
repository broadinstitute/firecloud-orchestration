package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.workbench.model.{ValueObject, WorkbenchGroupName}

object SamResource {

  case class ResourceId(value: String) extends ValueObject
  case class AccessPolicyName(value: String) extends ValueObject
  case class UserPolicy(resourceId: ResourceId, public: Boolean, accessPolicyName: AccessPolicyName, missingAuthDomainGroups: Set[WorkbenchGroupName], authDomainGroups: Set[WorkbenchGroupName])
}
