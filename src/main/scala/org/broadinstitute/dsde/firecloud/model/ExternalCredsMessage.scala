package org.broadinstitute.dsde.firecloud.model

import io.circe.Decoder

case class ExternalCredsMessage (providerName: String, userId: String)

object ExternalCredsMessage {
  implicit val externalCredsMessageDecoder: Decoder[ExternalCredsMessage] =
    Decoder.forProduct2("providerName", "userId")(ExternalCredsMessage.apply)
}
