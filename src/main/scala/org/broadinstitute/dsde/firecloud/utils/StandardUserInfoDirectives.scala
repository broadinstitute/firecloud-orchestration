package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{headerValueByName, onSuccess}
import org.broadinstitute.dsde.firecloud.dataaccess.SamDAO
import org.broadinstitute.dsde.firecloud.model.RegistrationInfoV2
import org.broadinstitute.dsde.firecloud.model.UserInfo

import scala.concurrent.{ExecutionContext, Future}

trait StandardUserInfoDirectives extends UserInfoDirectives {
  implicit val executionContext: ExecutionContext
  val samDAO: SamDAO

  val serviceAccountDomain = "\\S+@\\S+\\.iam\\.gserviceaccount\\.com".r

  private def isServiceAccount(email: String) = {
    serviceAccountDomain.pattern.matcher(email).matches
  }

  override def requireUserInfo: Directive1[UserInfo] = (
    headerValueByName("OIDC_access_token") &
      headerValueByName("OIDC_CLAIM_user_id") &
      headerValueByName("OIDC_CLAIM_expires_in") &
      headerValueByName("OIDC_CLAIM_email")
    ) tflatMap {
    case (token, userId, expiresIn, email) => {
      val userInfo = UserInfo(email, OAuth2BearerToken(token), expiresIn.toLong, userId)
      onSuccess(getWorkbenchUserEmailId(userInfo).map {
        case Some(petOwnerUser) => UserInfo(petOwnerUser.userEmail, OAuth2BearerToken(token), expiresIn.toLong, petOwnerUser.userSubjectId)
        case None => userInfo
      })
    }
  }

  private def getWorkbenchUserEmailId(userInfo:UserInfo):Future[Option[RegistrationInfoV2]] = {
    if (isServiceAccount(userInfo.userEmail)) {
      samDAO.getRegistrationStatusV2(userInfo)
    }
    else {
      Future.successful(None)
    }
  }
}
