package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.dataaccess.ShareLogApiServiceSpecShareLogDAO
import org.broadinstitute.dsde.firecloud.integrationtest.ElasticSearchShareLogDAOSpecFixtures
import org.broadinstitute.dsde.firecloud.mock.{MockUtils, SamMockserverUtils}
import org.broadinstitute.dsde.firecloud.model.ShareLog.ShareType
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, ShareLogService}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import org.scalatest.BeforeAndAfterAll
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

final class ShareLogApiServiceSpec extends BaseServiceSpec with ShareLogApiService with SamMockserverUtils with BeforeAndAfterAll {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  // mockserver to return an enabled user from Sam
  var mockSamServer: ClientAndServer = _

  override def afterAll(): Unit = mockSamServer.stop()

  override def beforeAll(): Unit = {
    mockSamServer = startClientAndServer(MockUtils.samServerPort)
    returnEnabledUser(mockSamServer)
  }

  final val sharingUser = UserInfo("fake1@gmail.com", OAuth2BearerToken(dummyToken), 3600L, "fake1")

  private def getUserHeaders(userId: String, email: String) = dummyUserIdHeaders(userId, dummyToken, email)

  private val getShareesPath = "/sharelog/sharees"

  private def makeGetShareesPath(shareType: ShareType.Value) = s"$getShareesPath?shareType=${shareType.toString}"

  val localShareLogDao = new ShareLogApiServiceSpecShareLogDAO

  override val shareLogServiceConstructor: () => ShareLogService = ShareLogService.constructor(app.copy(shareLogDAO = localShareLogDao))
  
  "ShareLogApiService" - {
    "when getting all sharees" in {
      Get(getShareesPath)  ~> getUserHeaders("fake1", "fake1@gmail.com") ~> sealRoute(shareLogServiceRoutes) ~> check {
        assertResult(OK) { status }
        responseAs[Seq[String]] should contain theSameElementsAs ElasticSearchShareLogDAOSpecFixtures.fixtureShares.map(_.sharee)
      }
    }
    "when getting workspace sharees" in {
      Get(makeGetShareesPath(ShareType.WORKSPACE)) ~> getUserHeaders("fake1", "fake1@gmail.com") ~> sealRoute(shareLogServiceRoutes) ~> check {
        assertResult(OK) { status }
        responseAs[Seq[String]] should contain theSameElementsAs ElasticSearchShareLogDAOSpecFixtures.fixtureShares.map(_.sharee)
      }
    }
  }
}

