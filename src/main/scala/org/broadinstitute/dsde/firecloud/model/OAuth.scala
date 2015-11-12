package org.broadinstitute.dsde.firecloud.model

/* NB: field names here are not Scala conventional. These are the field names returned in the raw
    response from Google, and if we ever need to, that response could be unmarshalled into this
    case class. If we used Scala-standard names the unmarshalling would break.
  */
case class OAuthTokens(
  access_token: String,
  token_type: String,
  expires_in: Long,
  refresh_token: Option[String],
  id_token: Option[String]
)

case class RawlsToken(refreshToken: String)

case class RawlsTokenDate(refreshTokenUpdatedDate: String)

case class OAuthException(message: String = null, cause: Throwable = null) extends RuntimeException(message, cause)

