package org.broadinstitute.dsde.test.api.orch

import org.scalatest.Assertion
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.enablers.Retrying
import org.scalatest.exceptions.TestFailedException
import org.scalactic.source

import javax.xml.parsers.FactoryConfigurationError

/**
  * Some helper functions to get asynchronous tests failing quickly
  * Should some day be made available to a wider codebase
  */
trait AsyncTestUtils extends Eventually {

  /**
    * Wraps a function in an eventually wrapped in a try-catch;
    * Functions as an eventually but will fail test without retrying if test-ending errors thrown in the test function
    *
    * @param timeout
    * @param interval
    * @param testFunction Test to run; should include a failAndEscape (could be within assertWithEscape)
    * @tparam T the result of the testFunction
    * @return The result of the testFunction (an assertion) if an error is not thrown
    */
  def eventuallyWithEscape[T](timeout: Timeout, interval: Interval)(testFunction: => T)(implicit retrying: Retrying[T], pos: source.Position): T =
    try {
      eventually(timeout, interval) {
        testFunction
      }
    } catch {
      case ex: FactoryConfigurationError => throw new TestEscapedException(ex.getMessage)
    }

  /**
    * eventuallyWithEscape but the eventually uses implicit timeout and interval
    *
    * @param testFunction Test to run; should include a failAndEscape (could be within assertWithEscape)
    * @tparam T the result of the testFunction
    * @return The result of the testFunction (an assertion) if an error is not thrown
    */
  def eventuallyWithEscape[T](testFunction: => T)(implicit retrying: Retrying[T], pos: source.Position): T =
    try {
      eventually {
        testFunction
      }
    } catch {
      case ex: FactoryConfigurationError => throw new TestEscapedException(ex.getMessage)
    }

  /**
    * Will fail a test that uses eventually instead of retrying
    *
    * @param message Error message
    * @return
    */
  def failAndEscape(message: String): Nothing = {
    throw new FactoryConfigurationError(message)
  }

  /**
    * Functions as an assertion unless some fail-immediately condition is met,
    * in which case it throws a test-failing error
    *
    * @param assertion     Scalatest assertion to be checked for success or failure
    * @param failCondition Condition that, if met, will end an asynchronous test with failure immediately
    * @param failMessage   Message to report in test failure
    * @return test assertion
    */
  def assertWithEscape(assertion: => Assertion, failCondition: => Assertion, failMessage: String): Assertion = {
    try {
      failCondition
    } catch {
      case ex: Exception => failAndEscape(s"$failMessage - failure due to: ${ex.getMessage}")
    }
    assertion
  }

}

/**
  * a TestFailedException that is thrown when an eventually is escaped early
  *
  * @param message              Failure message
  * @param failedCodeStackDepth necessary parameter for TestFailedException
  */
class TestEscapedException(
                            message: String,
                            failedCodeStackDepth: Int,
                          ) extends TestFailedException(message, failedCodeStackDepth) {
  def this(message: String) = this(message, 0)
}
