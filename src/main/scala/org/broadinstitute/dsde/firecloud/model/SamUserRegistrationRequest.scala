package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.workbench.model.ErrorReport

case class SamUserRegistrationRequest(
                                       acceptsTermsOfService: Boolean,
                                       userAttributes: SamUserAttributesRequest
                                     )
