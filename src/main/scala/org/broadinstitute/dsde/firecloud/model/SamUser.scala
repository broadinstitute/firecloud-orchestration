package org.broadinstitute.dsde.firecloud.model

import java.time.Instant

case class SamUser(id: String,
                   googleSubjectId: String,
                   email: String,
                   azureB2CId: String,
                   enabled: Boolean,
                   acceptedTosVersion: String,
                   createdAt: Instant,
                   registeredAt: Option[Instant],
                   updatedAt: Instant)
