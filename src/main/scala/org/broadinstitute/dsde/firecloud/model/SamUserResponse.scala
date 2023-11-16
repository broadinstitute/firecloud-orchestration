package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.workbench.model.{AzureB2CId, GoogleSubjectId, WorkbenchEmail, WorkbenchUserId}

import java.time.Instant

final case class SamUserResponse(
                                  id: WorkbenchUserId,
                                  googleSubjectId: Option[GoogleSubjectId],
                                  email: WorkbenchEmail,
                                  azureB2CId: Option[AzureB2CId],
                                  allowed: Boolean,
                                  createdAt: Instant,
                                  registeredAt: Option[Instant],
                                  updatedAt: Instant
                                ) {}
