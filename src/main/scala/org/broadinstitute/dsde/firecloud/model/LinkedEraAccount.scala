package org.broadinstitute.dsde.firecloud.model

import bio.terra.externalcreds.model.AdminLinkInfo
import org.joda.time.DateTime

object LinkedEraAccount {
  def apply(adminLinkInfo: AdminLinkInfo): LinkedEraAccount =
    LinkedEraAccount(adminLinkInfo.getUserId,
                     adminLinkInfo.getLinkedExternalId,
                     new DateTime(adminLinkInfo.getLinkExpireTime)
    )

  def unapply(linkedEraAccount: LinkedEraAccount): AdminLinkInfo =
    new AdminLinkInfo()
      .userId(linkedEraAccount.userId)
      .linkedExternalId(linkedEraAccount.linkedExternalId)
      .linkExpireTime(linkedEraAccount.linkExpireTime.toDate)
}

case class LinkedEraAccount(userId: String, linkedExternalId: String, linkExpireTime: DateTime)
