package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{AccessToken, RegistrationInfo}
import spray.http.StatusCodes

import scala.concurrent.{ExecutionContext, Future}

/**
  * Encapsulate bootstrap/startup checks into a single class. Note that individual DAOs may
  * also make their own startup checks, outside of this class.
  */
class StartupChecks(app: Application, registerSAs: Boolean = true)
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
    val lookup = manageRegistration(name, app.samDAO.getRegistrationStatus(implicitly(token)))
    lookup flatMap { isRegistered =>
      if (!isRegistered && registerSAs) {
        logger.warn(s"attempting to register $name ...")
        manageRegistration(name, app.samDAO.registerUser(implicitly(token)))
      } else {
        Future.successful(isRegistered)
      }
    }
  }

  private def manageRegistration(name: String, req: Future[RegistrationInfo]): Future[Boolean] = {
    req map { regInfo =>
      val fullyRegistered = regInfo.enabled.ldap && regInfo.enabled.allUsersGroup && regInfo.enabled.google
      if (fullyRegistered)
        logger.info(s"$name is properly registered.")
      else
        logger.error(s"***    $name is registered but not fully enabled: ${regInfo.enabled}!!    ***")
      fullyRegistered
    } recover {
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.NotFound) =>
        logger.error(s"***    $name is not registered!!    ***")
        false
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) =>
        logger.error(s"***    $name already exists!!    ***")
        false
      case e: Exception =>
        logger.error(s"***    Error on registration status for $name: ${e.getMessage}    ***")
        false
    }
  }


}
