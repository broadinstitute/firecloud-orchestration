package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.workbench.model.{AzureB2CId, GoogleSubjectId, WorkbenchEmail, WorkbenchUserId}

import java.time.Instant

case class SamUser(id: WorkbenchUserId,
                   googleSubjectId: Option[GoogleSubjectId],
                   email: WorkbenchEmail,
                   azureB2CId: Option[AzureB2CId],
                   enabled: Boolean,
                   createdAt: Instant,
                   registeredAt: Option[Instant],
                   updatedAt: Instant)
