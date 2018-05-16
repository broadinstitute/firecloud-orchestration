package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.dataaccess.{MockRawlsDAO, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.{UserInfo, optAkka2sprayStatus}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.{FireCloudException, FireCloudExceptionWithErrorReport}
import org.scalatest.FreeSpecLike
import spray.http.StatusCodes

import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.{Await, ExecutionContext, Future}


class PermissionsSupportSpec extends PermissionsSupport with FreeSpecLike {
  protected val rawlsDAO: RawlsDAO = new PermissionsSupportMockRawlsDAO
  implicit protected val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val dur:Duration = Duration(60, SECONDS)

  "tryIsGroupMember" - {
    "should return true if user is a member" in {
      assert( Await.result(tryIsGroupMember(UserInfo("", "alice"), "apples"), dur) )
      assert( Await.result(tryIsGroupMember(UserInfo("", "bob"), "bananas"), dur) )
    }
    "should return false if user is not a member" in {
      assert( !Await.result(tryIsGroupMember(UserInfo("", "alice"), "bananas"), dur) )
      assert( !Await.result(tryIsGroupMember(UserInfo("", "bob"), "apples"), dur) )
    }
    "should catch and wrap source exceptions" in {
      val ex = intercept[FireCloudExceptionWithErrorReport] {
        Await.result(tryIsGroupMember(UserInfo("", "failme"), "anygroup"), Duration.Inf)
      }
      assert(ex.errorReport.message == "Unable to query for group membership status.")
    }
  }

  "asGroupMember" - {
    "should allow inner function to succeed if user is a member" in {
      implicit val userInfo = UserInfo("", "alice")
      def command = asGroupMember("apples") { Future.successful(RequestComplete(StatusCodes.OK)) }
      val x = Await.result(command, dur)
      assertResult(RequestComplete(StatusCodes.OK)) { x }
    }
    "should throw FireCloudExceptionWithErrorReport if user is not a member" in {
      implicit val userInfo = UserInfo("", "bob")
      def command = asGroupMember("apples") { Future.successful(RequestComplete(StatusCodes.OK)) }
      val x = intercept[FireCloudExceptionWithErrorReport] {
        Await.result(command, dur)
      }
      assertResult(Some(StatusCodes.Forbidden)) { optAkka2sprayStatus(x.errorReport.statusCode) }
      assertResult("You must be in the appropriate group.") { x.errorReport.message }
    }
  }

  "asTrialCampaignManager" - {
    "should allow inner function to succeed if user is a member" in {
      implicit val userInfo = UserInfo("", "charlie")
      def command = asTrialCampaignManager { Future.successful(RequestComplete(StatusCodes.OK)) }
      val x = Await.result(command, dur)
      assertResult(RequestComplete(StatusCodes.OK)) { x }
    }
    "should throw FireCloudExceptionWithErrorReport if user is not a member" in {
      implicit val userInfo = UserInfo("", "bob")
      def command = asTrialCampaignManager { Future.successful(RequestComplete(StatusCodes.OK)) }
      val x = intercept[FireCloudExceptionWithErrorReport] {
        Await.result(command, dur)
      }
      assertResult(Some(StatusCodes.Forbidden)) { optAkka2sprayStatus(x.errorReport.statusCode) }
      assertResult("You must be in the appropriate group.") { x.errorReport.message }
    }
  }
}

class PermissionsSupportMockRawlsDAO extends MockRawlsDAO {
  private val groupMap = Map(
    "apples" -> Seq("alice"),
    "bananas" -> Seq("bob"),
    "trial_managers" -> Seq("charlie") // the name "trial_managers" is defined in reference.conf
  )

  override def isGroupMember(userInfo: UserInfo, groupName: String): Future[Boolean] = {
    userInfo.id match {
      case "failme" => Future.failed(new Exception("intentional exception for unit tests"))
      case _ => Future.successful(groupMap.getOrElse(groupName, Seq.empty[String]).contains(userInfo.id))
    }
  }

}
