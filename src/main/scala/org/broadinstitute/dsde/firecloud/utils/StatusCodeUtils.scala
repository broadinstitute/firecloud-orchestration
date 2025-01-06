package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.model.{StatusCode, StatusCodes}

import scala.util.Try

trait StatusCodeUtils {

  /**
    * Safely translates an integer to a StatusCode. This method avoids the RuntimeException
    * thrown by StatusCode.int2StatusCode when supplied with an unknown code and returns
    * a default status code instead.
    *
    * @param intCode the integer value to translate
    * @param default the code to return if the integer value is unknown;
    *                defaults to Internal Server Error
    * @return the final status code
    */
  def statusCodeFrom(intCode: Int, default: Option[StatusCode] = None): StatusCode =
    Try(StatusCode.int2StatusCode(intCode)).getOrElse(
      default.getOrElse(
        StatusCodes.custom(intCode,
                           reason = "unknown status",
                           defaultMessage = "unknown status",
                           isSuccess = false,
                           allowsEntity = true
        )
      )
    )

}
