package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockMethodsServer
import org.broadinstitute.dsde.firecloud.model.MethodRepository._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

class MethodsServiceSpec extends ServiceSpec with MethodsService {

  def actorRefFactory = system

  import org.broadinstitute.dsde.firecloud.model.ErrorReport.errorReportRejectionHandler

  override def beforeAll(): Unit = {
    MockMethodsServer.startMethodsServer()
  }

  override def afterAll(): Unit = {
    MockMethodsServer.stopMethodsServer()
  }

  "MethodsService" - {

    "when calling GET on the /methods path" - {
      "valid methods are returned" in {
        Get("/methods") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val entities = responseAs[List[Method]]
          entities shouldNot be(empty)
          entities foreach {
            e: Method =>
              e.namespace shouldNot be(empty)
          }
        }
      }
    }

    "when calling GET on the /methods path without a valid authentication token" - {
      "Found (302 redirect) response is returned" in {
        Get("/methods") ~> sealRoute(routes) ~> check {
          status should equal(Found)
        }
      }
    }

    "when calling POST on the /methods path" - {
      "MethodNotAllowed error is returned" in {
        Put("/methods") ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          errorReportCheck("Agora", MethodNotAllowed)
        }
      }
    }

    "when calling PUT on the /methods path" - {
      "MethodNotAllowed error is returned" in {
        Post("/methods") ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          errorReportCheck("Agora", MethodNotAllowed)
        }
      }
    }


    "when calling GET on the /configurations path" - {
      "valid methods are returned" in {
        Get("/configurations") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val entities = responseAs[List[Configuration]]
          entities shouldNot be(empty)
          entities foreach {
            e: Configuration =>
              e.namespace shouldNot be(empty)
          }
        }
      }
    }

    "when calling GET on the /configurations path without a valid authentication token" - {
      "Found (302 redirect) response is returned" in {
        Get("/configurations") ~> sealRoute(routes) ~> check {
          status should equal(Found)
        }
      }
    }

    "when calling POST on the /configurations path" - {
      "MethodNotAllowed error is returned" in {
        Put("/configurations") ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          errorReportCheck("Agora", MethodNotAllowed)
        }
      }
    }

    "when calling PUT on the /configurations path" - {
      "MethodNotAllowed error is returned" in {
        Post("/configurations") ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          errorReportCheck("Agora", MethodNotAllowed)
        }
      }
    }

    "when calling GET on a given method" - {
      "A list of (translated agora-to) FireCloud permissions is returned" in {
        Get("/configurations/broad-dsde-dev/EddieFastaCounter/1/permissions") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val perms = responseAs[List[FireCloudPermission]]
          perms foreach {
            p: FireCloudPermission =>
              p.user shouldNot be(empty)
              p.role shouldBe Some(List)
              val pCount=p.role.##
              val pCountGoodFlag=(0<=pCount && pCount<=5)
              pCountGoodFlag shouldBe true

          }
        }
      }
    }

    "when calling GET on a given method" - {
      "A list of (translated agora-to) FireCloud permissions is returned" in {
        Get("/methods/broad-dsde-dev/EddieFastaCounter/1/permissions") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val perms = responseAs[List[FireCloudPermission]]
          perms foreach {
            p: FireCloudPermission =>
             p.user shouldNot be(empty)
             p.role shouldBe Some(List)
             val pCount=p.role.##
             val pCountGoodFlag=(0<=pCount && pCount<=5)
             pCountGoodFlag shouldBe true

          }
        }
      }
    }

}}
