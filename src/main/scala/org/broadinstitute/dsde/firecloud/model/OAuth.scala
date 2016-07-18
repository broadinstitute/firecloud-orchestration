package org.broadinstitute.dsde.firecloud.model

import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse

/* NB: field names here are not Scala conventional. These are the field names returned in the raw
    response from Google, and if we ever need to, that response could be unmarshalled into this
    case class. If we used Scala-standard names the unmarshalling would break.
  */
case class OAuthTokens(
  access_token: String,
  token_type: String,
  expires_in: Long,
  refresh_token: Option[String],
  id_token: Option[String],
  subject_id: Option[String]
)

object OAuthTokens {
  def apply(gcsTokenResponse: GoogleTokenResponse): OAuthTokens = {
    val refreshToken = gcsTokenResponse.getRefreshToken match {
      case null => None
      case x => Some(x)
    }

    val List(idToken: Option[String], subjectId: Option[String]) = gcsTokenResponse.getIdToken match {
      case null => List(None, None)
      case x =>
        List(Some(x), Some(gcsTokenResponse.parseIdToken().getPayload.getSubject))
    }

    OAuthTokens(
      gcsTokenResponse.getAccessToken,
      gcsTokenResponse.getTokenType,
      gcsTokenResponse.getExpiresInSeconds,
      refreshToken,
      idToken,
      subjectId
    )
  }
}

case class RawlsToken(refreshToken: String)

case class RawlsTokenDate(refreshTokenUpdatedDate: String)

case class OAuthException(message: String = null, cause: Throwable = null) extends RuntimeException(message, cause)

