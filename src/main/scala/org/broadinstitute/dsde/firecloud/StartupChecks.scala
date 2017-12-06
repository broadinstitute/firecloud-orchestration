package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.AccessToken
import spray.http.StatusCodes

import scala.concurrent.{ExecutionContext, Future}

/**
  * Encapsulate bootstrap/startup checks into a single class. Note that individual DAOs may
  * also make their own startup checks, outside of this class.
  */
class StartupChecks(app: Application)
                   (implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
                    extends LazyLogging {

  def check: Future[Boolean] = {
    val checks = Future.sequence(Seq(
      isAdminSARegistered,
      isTrialBillingSARegistered
    ))

    checks map { checkResults =>
      val hasFailures = checkResults.exists(!_)
      if (hasFailures) {
        logger.error(
          "\n*********************************************************" +
          "\n*********************************************************" +
          "\n*********************************************************" +
          "\n***     BEWARE: AT LEAST ONE STARTUP CHECK FAILED     ***" +
          "\n***       SEE PREVIOUS LOG MESSAGES FOR DETAILS       ***" +
          "\n*********************************************************" +
          "\n*********************************************************" +
          "\n*********************************************************")
      } else {
        logger.info("***    all startup checks succeeded.    ***")
      }
      !hasFailures
    }
  }

  private def isAdminSARegistered:Future[Boolean] =
    isServiceAccountRegistered("Admin SA",
      AccessToken(app.googleServicesDAO.getAdminUserAccessToken))

  private def isTrialBillingSARegistered:Future[Boolean] =
    isServiceAccountRegistered("Free trial billing SA",
      AccessToken(app.googleServicesDAO.getTrialBillingManagerAccessToken))


  private def isServiceAccountRegistered(name: String, token: AccessToken): Future[Boolean] = {
    app.samDAO.getRegistrationStatus(implicitly(token)) map { regInfo =>
      if (regInfo.enabled.ldap)
        logger.info(s"$name is properly registered.")
      else
        logger.error(s"***    $name is registered but not fully enabled!!    ***")
      regInfo.enabled.ldap
    } recover {
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.NotFound) =>
        logger.error(s"***    $name is not registered!!    ***")
        false
      case e: Exception =>
        logger.error(s"***    Error getting registration status for $name: ${e.getMessage}    ***")
        false
    }
  }

}
