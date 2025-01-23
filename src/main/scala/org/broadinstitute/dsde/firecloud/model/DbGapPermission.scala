package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.workbench.model.ValueObject

case class PhsId(value: String) extends ValueObject
case class ConsentGroup(value: String) extends ValueObject
case class DbGapPermission(phsId: PhsId, consentGroup: ConsentGroup)
