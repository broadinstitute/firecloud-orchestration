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
            val newMethod = editMethodResponse.method
            assertResult("expect") {newMethod.namespace.get}
            assertResult("success") {newMethod.name.get}
            assertResult(2) {newMethod.snapshotId.get}
            assertResult("synopsis1") {newMethod.synopsis.get}
            assertResult("doc1") {newMethod.documentation.get}
            assertResult("payload1") {newMethod.payload.get}

            assertResult(1) {dao.postMethodCalls.length}
            assertResult(("expect","success","synopsis1","doc1","payload1")) {dao.postMethodCalls.head}
            assertResult(1) {dao.getMethodPermissionsCalls.length}
            assertResult(("expect","success",1)) {dao.getMethodPermissionsCalls.head}
            assertResult(1) {dao.postMethodPermissionsCalls.length}
            assertResult(("expect","success",editMethodResponse.method.snapshotId.get, List(MockAgoraDAO.agoraPermission))) {dao.postMethodPermissionsCalls.head}
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
      "should result in partial success" in {
        val req = EditMethodRequest(MethodId("exceptions","permGetError",1), "synopsis", "doc", "payload", redactOldSnapshot=true)
        val result = Await.result(aes.editMethod(req), dur)
        result match {
          case RequestComplete((status:StatusCode, editMethodResponse:EditMethodResponse)) =>
            assertResult(OK) {status}
            // creating the new method succeeded:
            assertResult(1) {dao.postMethodCalls.length}
            assertResult(("exceptions","permGetError","synopsis","doc","payload")) {dao.postMethodCalls.head}
            // but get permissions errored:
            assertResult(1) {dao.getMethodPermissionsCalls.length}
            assertResult(("exceptions","permGetError",1)) {dao.getMethodPermissionsCalls.head}
            // and neither post nor redact were attempted:
            assertResult(0) {dao.postMethodPermissionsCalls.length}
            assertResult(0) {dao.redactMethodCalls.length}
            // also, we got some kind of error message:
            assert(editMethodResponse.message.isDefined)

          case x => fail(s"expected RequestComplete; got $x")
        }
      }
    }
    "when encountering an error during permission setting" - {
      "should result in partial success" in {
        val req = EditMethodRequest(MethodId("exceptions","permPostError",1), "synopsis", "doc", "payload", redactOldSnapshot=true)
        val result = Await.result(aes.editMethod(req), dur)
        result match {
          case RequestComplete((status:StatusCode, editMethodResponse:EditMethodResponse)) =>
            assertResult(OK) {status}
            // creating the new method succeeded:
            assertResult(1) {dao.postMethodCalls.length}
            assertResult(("exceptions","permPostError","synopsis","doc","payload")) {dao.postMethodCalls.head}
            // getting permissions succeeded:
            assertResult(1) {dao.getMethodPermissionsCalls.length}
            assertResult(("exceptions","permPostError",1)) {dao.getMethodPermissionsCalls.head}
            // but post permissions errored:
            assertResult(1) {dao.postMethodPermissionsCalls.length}
            assertResult(("exceptions","permPostError",2,List(MockAgoraDAO.agoraPermission))) {dao.postMethodPermissionsCalls.head}
            // and redact was not attempted:
            assertResult(0) {dao.redactMethodCalls.length}
            // also, we got some kind of error message:
            assert(editMethodResponse.message.isDefined)

          case x => fail(s"expected RequestComplete; got $x")
        }
      }
    }
    "when encountering an error during redaction of copy source" - {
      "should result in partial success" in {
        val req = EditMethodRequest(MethodId("exceptions","redactError",1), "synopsis", "doc", "payload", redactOldSnapshot=true)
        val result = Await.result(aes.editMethod(req), dur)
        result match {
          case RequestComplete((status:StatusCode, editMethodResponse:EditMethodResponse)) =>
            assertResult(OK) {status}
            // creating the new method succeeded:
            assertResult(1) {dao.postMethodCalls.length}
            assertResult(("exceptions","redactError","synopsis","doc","payload")) {dao.postMethodCalls.head}
            // getting permissions succeeded:
            assertResult(1) {dao.getMethodPermissionsCalls.length}
            assertResult(("exceptions","redactError",1)) {dao.getMethodPermissionsCalls.head}
            // posting permissions succeeded:
            assertResult(1) {dao.postMethodPermissionsCalls.length}
            assertResult(("exceptions","redactError",2,List(MockAgoraDAO.agoraPermission))) {dao.postMethodPermissionsCalls.head}
            // but redact errored:
            assertResult(1) {dao.redactMethodCalls.length}
            assertResult(("exceptions","redactError",1)) {dao.redactMethodCalls.head}
            // also, we got some kind of error message:
            assert(editMethodResponse.message.isDefined)

          case x => fail(s"expected RequestComplete; got $x")
        }
      }
    }
    "when specifying redact=false" - {
      "should not redact" in {
        val req = EditMethodRequest(MethodId("expect","success",1), "synopsis", "doc", "payload", redactOldSnapshot=false)
        val result = Await.result(aes.editMethod(req), dur)
        result match {
          case RequestComplete((status:StatusCode, editMethodResponse:EditMethodResponse)) =>
            assertResult(OK) {status}
            // creating the new method succeeded:
            assertResult(1) {dao.postMethodCalls.length}
            assertResult(("expect","success","synopsis","doc","payload")) {dao.postMethodCalls.head}
            // getting permissions succeeded:
            assertResult(1) {dao.getMethodPermissionsCalls.length}
            assertResult(("expect","success",1)) {dao.getMethodPermissionsCalls.head}
            // posting permissions succeeded:
            assertResult(1) {dao.postMethodPermissionsCalls.length}
            assertResult(("expect","success",2,List(MockAgoraDAO.agoraPermission))) {dao.postMethodPermissionsCalls.head}
            // but redact was not run:
            assertResult(0) {dao.redactMethodCalls.length}

          case x => fail(s"expected RequestComplete; got $x")
        }
      }
    }
  }



}
