package org.broadinstitute.dsde.firecloud.model

/**
  * Created by davidan on 9/30/16.
  */

// this is not a comprehensive list of what the endpoint can return.
// sadly, google returns strings for all values, even the numeric ones
case class ObjectMetadata(
  bucket: String,
  crc32c: String,
  etag: String,
  generation: String,
  id: String,
  md5Hash: Option[String],
  mediaLink: Option[String],
  name: String,
  size: String,
  storageClass: String,
  timeCreated: Option[String],
  updated: String,
  contentDisposition: Option[String],
  contentEncoding: Option[String],
  contentType: Option[String],
  estimatedCostUSD: Option[BigDecimal]
)

