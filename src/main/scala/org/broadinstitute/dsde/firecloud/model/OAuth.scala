package org.broadinstitute.dsde.firecloud.model


case class TokenResponse(
  access_token: String,
  token_type: String,
  expires_in: Int,
  refresh_token: Option[String],
  id_token: Option[String]
)
