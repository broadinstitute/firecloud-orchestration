package org.broadinstitute.dsde.firecloud.service

import akka.testkit.TestActorRef
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.dataaccess.{MockAgoraDAO, StatefulMockAgoraDAO}
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{EditMethodRequest, EditMethodResponse, MethodId}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.scalatest.BeforeAndAfterEach
import spray.http.StatusCode
import spray.http.StatusCodes._

import scala.concurrent.Await
import scala.concurrent.duration._

class AgoraEntityServiceSpec extends BaseServiceSpec with BeforeAndAfterEach {

  val dur = Duration(2, MINUTES)

  var dao: StatefulMockAgoraDAO = _
  var aes: AgoraEntityService = _

  override def beforeEach = {
    // we create a new dao, and a new service using that new dao, for each test.
    dao = new StatefulMockAgoraDAO
    val newApp = app.copy(agoraDAO = dao)
    val agoraEntityServiceConstructor: (UserInfo) => AgoraEntityService = AgoraEntityService.constructor(newApp)
    aes = TestActorRef(AgoraEntityService.props(agoraEntityServiceConstructor, UserInfo("token","id"))).underlyingActor
  }

  "AgoraEntityService" - {
    // (at least) one test in which we verify details of every call and response
    "when copying a method" - {
      "should return the new copy" in {
        val req: EditMethodRequest = EditMethodRequest(MethodId("expect","success",1), "synopsis1", "doc1", "payload1", redactOldSnapshot=true)
        val result = Await.result(aes.editMethod(req), dur)
        result match {
          case RequestComplete((status:StatusCode, editMethodResponse:EditMethodResponse)) =>
            assertResult(OK) {status}
            assertResult("expect") {editMethodResponse.method.namespace}
            assertResult("success") {editMethodResponse.method.name}
            // TODO: I think editMethod() should return the newly-created method in full; if we do so, validate everything here

            assertResult(1) {dao.postMethodCalls.length}
            assertResult(("expect","success","synopsis1","doc1","payload1")) {dao.postMethodCalls.head}
            assertResult(1) {dao.getMethodPermissionsCalls.length}
            assertResult(("expect","success",1)) {dao.getMethodPermissionsCalls.head}
            assertResult(1) {dao.postMethodPermissionsCalls.length}
            assertResult(("expect","success",editMethodResponse.method.snapshotId, List(MockAgoraDAO.agoraPermission))) {dao.postMethodPermissionsCalls.head}
            assertResult(1) {dao.redactMethodCalls.length}
            assertResult(("expect","success",1)) {dao.redactMethodCalls.head}

          case x => fail(s"expected RequestComplete; got $x")
        }
      }
    }
    "when encountering an error during snapshot creation" - {
      "should throw an exception" in {
        val req: EditMethodRequest = EditMethodRequest(MethodId("exceptions","postError",1), "synopsis", "doc", "payload", redactOldSnapshot=true)
        intercept[FireCloudExceptionWithErrorReport] {
          Await.result(aes.editMethod(req), dur)
        }
        assertResult(1) {dao.postMethodCalls.length}
        assertResult(("exceptions","postError","synopsis","doc","payload")) {dao.postMethodCalls.head}
        // ensure that if we failed to create the new snapshot, we abort and don't try to get/set permissions or redact
        assertResult(0) {dao.getMethodPermissionsCalls.length}
        assertResult(0) {dao.postMethodPermissionsCalls.length}
        assertResult(0) {dao.redactMethodCalls.length}

      }
    }
    "when encountering an error during permission retrieval" - {
      "should throw an exception" in {
        fail("not implemented")
        // TODO: flesh out test; ensure we don't try to set permissions or redact
      }
    }
    "when encountering an error during permission setting" - {
      "should throw an exception" in {
        fail("not implemented")
        // TODO: flesh out test; ensure we didn't try to redact
      }
    }
    "when encountering an error during redaction of copy source" - {
      "should throw an exception" in {
        fail("not implemented")
        // TODO: flesh out test; ensure we make all calls; the error occurs on the last call
      }
    }
    "when specifying redact=false" - {
      "should not redact" in {
        fail("not implemented")
        // TODO: flesh out test; ensure the overall call succeeded, but we didn't attempt redact
      }
    }
  }



}
