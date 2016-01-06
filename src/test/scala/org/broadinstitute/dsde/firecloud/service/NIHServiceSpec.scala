package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._

import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._


class NIHServiceSpec extends ServiceSpec with NIHService {

  def actorRefFactory = system
  var profileServer: ClientAndServer = _
  val uniqueId = "1234"

  override def beforeAll(): Unit = {

    profileServer = startClientAndServer(thurloeServerPort)

    profileServer
      .when(request().withMethod("GET").withPath(UserService.remoteGetAllPath.format(uniqueId)))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          .withBody("{}")
      )
  }

  override def afterAll(): Unit = {
    profileServer.stop()
  }

  val targetUri = "/nih/status"

  "NIHService" - {

    "when GET-ting a profile with no NIH info" - {
      "NotFound response is returned" in {
        respondWith()

        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
        }
      }
    }

    "when GET-ting a profile with missing NIH username" - {
      "NotFound response is returned" in {
        respondWith(
          lastLinkTime = Some(222),
          linkExpireTime = Some(333),
          isDbgapAuthorized = Some(true)
        )
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
        }
      }
    }

    "when GET-ting a profile with missing lastLinkTime" - {
      "loginRequired is true" in {
        respondWith(
          linkedNihUsername = Some("nihuser"),
          linkExpireTime = Some(333),
          isDbgapAuthorized = Some(true)
        )
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val nihStatus = responseAs[NIHStatus]
          nihStatus.loginRequired shouldBe(true)
        }
      }
    }

    "when GET-ting a profile with missing linkExpireTime" - {
      "loginRequired is true" in {
        respondWith(
          linkedNihUsername = Some("nihuser"),
          lastLinkTime = Some(222),
          isDbgapAuthorized = Some(true)
        )
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val nihStatus = responseAs[NIHStatus]
          nihStatus.loginRequired shouldBe(true)
        }
      }
    }

    "when GET-ting a profile with recent lastLinkTime and future linkExpireTime" - {
      "loginRequired is false" in {

        val lastLinkTime = DateUtils.now
        val linkExpireTime = DateUtils.nowPlus30Days

        respondWith(
          linkedNihUsername = Some("nihuser"),
          lastLinkTime = Some(lastLinkTime),
          linkExpireTime = Some(linkExpireTime),
          isDbgapAuthorized = Some(true)
        )
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val nihStatus = responseAs[NIHStatus]
          nihStatus.loginRequired shouldBe(false)
        }
      }
    }

    "when GET-ting a profile with recent lastLinkTime and an expired linkExpireTime" - {
      "loginRequired is true" in {

        val lastLinkTime = DateUtils.now
        val linkExpireTime = DateUtils.nowMinus24Hours

        respondWith(
          linkedNihUsername = Some("nihuser"),
          lastLinkTime = Some(lastLinkTime),
          linkExpireTime = Some(linkExpireTime),
          isDbgapAuthorized = Some(true)
        )
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val nihStatus = responseAs[NIHStatus]
          nihStatus.loginRequired shouldBe(true)
        }
      }
    }

  }

  def respondWith(
                   linkedNihUsername: Option[String] = None,
                   lastLinkTime: Option[Long] = None,
                   linkExpireTime: Option[Long] = None,
                   isDbgapAuthorized: Option[Boolean] = None) = {
    profileServer.clear(request.withMethod("GET").withPath(UserService.remoteGetAllPath.format(uniqueId)))
    profileServer
      .when(request().withMethod("GET").withPath(UserService.remoteGetAllPath.format(uniqueId)))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          .withBody(generateResponse(linkedNihUsername, lastLinkTime, linkExpireTime, isDbgapAuthorized))
      )
  }


  def generateResponse(
                        linkedNihUsername: Option[String] = None,
                        lastLinkTime: Option[Long] = None,
                        linkExpireTime: Option[Long] = None,
                        isDbgapAuthorized: Option[Boolean] = None): String = {

    val kvps: List[FireCloudKeyValue] = List(
      FireCloudKeyValue(Some("name"), Some("testName")),
      FireCloudKeyValue(Some("email"), Some("testEmail")),
      FireCloudKeyValue(Some("institution"), Some("testInstitution")),
      FireCloudKeyValue(Some("pi"), Some("testPI"))
    ) ::: (linkedNihUsername match {
      case Some(x:String) => List(FireCloudKeyValue(Some("linkedNihUsername"), Some(x)))
      case _ => List.empty[FireCloudKeyValue]
    }) ::: (lastLinkTime match {
      case Some(x:Long) => List(FireCloudKeyValue(Some("lastLinkTime"), Some(x.toString)))
      case _ => List.empty[FireCloudKeyValue]
    }) ::: (linkExpireTime match {
      case Some(x:Long) => List(FireCloudKeyValue(Some("linkExpireTime"), Some(x.toString)))
      case _ => List.empty[FireCloudKeyValue]
    }) ::: (isDbgapAuthorized match {
      case Some(x:Boolean) => List(FireCloudKeyValue(Some("isDbgapAuthorized"), Some(x.toString)))
      case _ => List.empty[FireCloudKeyValue]
    })

    val profileWrapper = ProfileWrapper(uniqueId,kvps)

    profileWrapper.toJson.prettyPrint
  }




}
