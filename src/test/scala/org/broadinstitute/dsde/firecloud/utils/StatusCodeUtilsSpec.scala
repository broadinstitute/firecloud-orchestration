package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

class StatusCodeUtilsSpec extends AnyFlatSpec with StatusCodeUtils {

  behavior of "statusCodeFrom"

  val expectedCases: Map[Int, StatusCode] = Map(
    200 -> OK,
    404 -> NotFound,
    503 -> ServiceUnavailable
  )

  expectedCases.foreach { case (intCode, statusCode) =>
    it should s"handle known code $intCode" in {
      statusCodeFrom(intCode) shouldBe statusCode
    }
  }

  val unknownCodes: List[Int] = List(-1, 0, 42, 222, 555)

  unknownCodes.foreach { intCode =>
    it should s"create a custom status code $intCode for unknown values" in {
      val actual = statusCodeFrom(intCode)
      actual.intValue() shouldBe intCode
      actual.isSuccess() shouldBe false
      actual.defaultMessage() shouldBe "unknown status"
    }
  }

  unknownCodes.foreach { intCode =>
    it should s"default unknown code $intCode to the caller-supplied default" in {
      statusCodeFrom(intCode, Option(ImATeapot)) shouldBe ImATeapot
    }
  }

}
