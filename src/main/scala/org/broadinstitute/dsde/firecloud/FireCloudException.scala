package org.broadinstitute.dsde.firecloud

import spray.http.{StatusCodes, StatusCode}

class FireCloudException(message: String = null, cause: Throwable = null) extends Exception(message, cause)

/**
 * Specifies which HTTP status code to return if/when this exception gets bubbled up.
 */
class FireCloudExceptionWithStatusCode(message: String = null, cause: Throwable = null,
  statusCode: StatusCode = StatusCodes.InternalServerError) extends FireCloudException(message, cause) {
  def getCode = statusCode
}
