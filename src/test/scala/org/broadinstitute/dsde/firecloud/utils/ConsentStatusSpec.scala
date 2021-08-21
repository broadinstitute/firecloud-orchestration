package org.broadinstitute.dsde.firecloud.utils

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import org.broadinstitute.dsde.firecloud.dataaccess.ReportsSubsystemStatus
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{ExecutionContext, Future}

class ConsentStatusSpec extends AnyFreeSpec with ScalaFutures with SprayJsonSupport with ReportsSubsystemStatus {

  override def status: Future[SubsystemStatus] = {
    getStatusFromDropwizardChecks(Future.failed(new Exception("exception")))
  }

  override def serviceName: String = "ConsentStatusSpec"

  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val system: ActorSystem = ActorSystem("ConsentStatusSpec")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val defaultPatience: PatienceConfig = PatienceConfig(timeout = Span(30, Seconds), interval = Span(5, Millis))

  "ReportsSubsystemStatus.getStatusFromDropwizardChecks" - {
    "successfully parse OK ConsentStatus, no errors" in {
      val consentStatusJsonNoErrors: String = "{\n\"ok\": true,\n\"degraded\": false,\n\"systems\": {\n\"deadlocks\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479384812,\n\"duration\": 6,\n\"timestamp\": \"2021-08-20T17:09:44.812Z\"\n},\n\"elastic-search\": {\n\"healthy\": true,\n\"message\": \"ClusterHealth is GREEN\",\n\"error\": null,\n\"details\": null,\n\"time\": 1629479382379,\n\"duration\": 1116,\n\"timestamp\": \"2021-08-20T17:09:42.379Z\"\n},\n\"google-cloud-storage\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479384804,\n\"duration\": 1987,\n\"timestamp\": \"2021-08-20T17:09:44.804Z\"\n},\n\"ontology\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": {\n\"ok\": true,\n\"systems\": {\n\"deadlocks\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479385314,\n\"duration\": 0,\n\"timestamp\": \"2021-08-20T17:09:45.314Z\"\n},\n\"elastic-search\": {\n\"healthy\": true,\n\"message\": \"ClusterHealth is GREEN\",\n\"error\": null,\n\"details\": null,\n\"time\": 1629479385299,\n\"duration\": 1,\n\"timestamp\": \"2021-08-20T17:09:45.299Z\"\n},\n\"google-cloud-storage\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479385314,\n\"duration\": 14,\n\"timestamp\": \"2021-08-20T17:09:45.314Z\"\n}\n}\n},\n\"time\": 1629479385506,\n\"duration\": 694,\n\"timestamp\": \"2021-08-20T17:09:45.506Z\"\n},\n\"postgresql\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479382792,\n\"duration\": 433,\n\"timestamp\": \"2021-08-20T17:09:42.792Z\"\n},\n\"sam\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": {\n\"ok\": true,\n\"systems\": {\n\"GooglePubSub\": {\n\"ok\": true\n},\n\"Database\": {\n\"ok\": true\n},\n\"GoogleGroups\": {\n\"ok\": true\n},\n\"GoogleIam\": {\n\"ok\": true\n},\n\"OpenDJ\": {\n\"ok\": true\n}\n}\n},\n\"time\": 1629479385898,\n\"duration\": 392,\n\"timestamp\": \"2021-08-20T17:09:45.898Z\"\n}\n}\n}"
      val subsystemFuture = callGetStatusFromDropwizardChecks(consentStatusJsonNoErrors)
      whenReady(subsystemFuture) { f =>
        assertResult(true)(f.ok)
        assertResult(true)(f.messages.isEmpty)
      }
    }
    "successfully parse OK ConsentStatus with errors" in {
      val consentStatusJsonSomeErrors: String = "{\n\"ok\": true,\n\"degraded\": true,\n\"systems\": {\n\"deadlocks\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479384812,\n\"duration\": 6,\n\"timestamp\": \"2021-08-20T17:09:44.812Z\"\n},\n\"elastic-search\": {\n\"healthy\": false,\n\"message\": \"ClusterHealth is RED\",\n\"error\": null,\n\"details\": null,\n\"time\": 1629479382379,\n\"duration\": 1116,\n\"timestamp\": \"2021-08-20T17:09:42.379Z\"\n},\n\"google-cloud-storage\": {\n\"healthy\": false,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479384804,\n\"duration\": 1987,\n\"timestamp\": \"2021-08-20T17:09:44.804Z\"\n},\n\"ontology\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": {\n\"ok\": true,\n\"systems\": {\n\"deadlocks\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479385314,\n\"duration\": 0,\n\"timestamp\": \"2021-08-20T17:09:45.314Z\"\n},\n\"elastic-search\": {\n\"healthy\": true,\n\"message\": \"ClusterHealth is GREEN\",\n\"error\": null,\n\"details\": null,\n\"time\": 1629479385299,\n\"duration\": 1,\n\"timestamp\": \"2021-08-20T17:09:45.299Z\"\n},\n\"google-cloud-storage\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479385314,\n\"duration\": 14,\n\"timestamp\": \"2021-08-20T17:09:45.314Z\"\n}\n}\n},\n\"time\": 1629479385506,\n\"duration\": 694,\n\"timestamp\": \"2021-08-20T17:09:45.506Z\"\n},\n\"postgresql\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479382792,\n\"duration\": 433,\n\"timestamp\": \"2021-08-20T17:09:42.792Z\"\n},\n\"sam\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": {\n\"ok\": true,\n\"systems\": {\n\"GooglePubSub\": {\n\"ok\": true\n},\n\"Database\": {\n\"ok\": true\n},\n\"GoogleGroups\": {\n\"ok\": true\n},\n\"GoogleIam\": {\n\"ok\": true\n},\n\"OpenDJ\": {\n\"ok\": true\n}\n}\n},\n\"time\": 1629479385898,\n\"duration\": 392,\n\"timestamp\": \"2021-08-20T17:09:45.898Z\"\n}\n}\n}"
      val subsystemFuture = callGetStatusFromDropwizardChecks(consentStatusJsonSomeErrors)
      whenReady(subsystemFuture) { f =>
        assertResult(true)(f.ok)
        // This is counterintuitive. The original logic does not add messages if the overall status
        // is OK. We should maintain that to be backwards compatible with any other downstream
        // checks that might break.
        assertResult(true)(f.messages.isEmpty)
      }
    }
    "successfully parse NOT OK ConsentStatus with errors" in {
      val consentStatusJsonNotOK: String = "{\n\"ok\": false,\n\"degraded\": true,\n\"systems\": {\n\"deadlocks\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479384812,\n\"duration\": 6,\n\"timestamp\": \"2021-08-20T17:09:44.812Z\"\n},\n\"elastic-search\": {\n\"healthy\": false,\n\"message\": \"ClusterHealth is RED\",\n\"error\": null,\n\"details\": null,\n\"time\": 1629479382379,\n\"duration\": 1116,\n\"timestamp\": \"2021-08-20T17:09:42.379Z\"\n},\n\"google-cloud-storage\": {\n\"healthy\": false,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479384804,\n\"duration\": 1987,\n\"timestamp\": \"2021-08-20T17:09:44.804Z\"\n},\n\"ontology\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": {\n\"ok\": true,\n\"systems\": {\n\"deadlocks\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479385314,\n\"duration\": 0,\n\"timestamp\": \"2021-08-20T17:09:45.314Z\"\n},\n\"elastic-search\": {\n\"healthy\": true,\n\"message\": \"ClusterHealth is GREEN\",\n\"error\": null,\n\"details\": null,\n\"time\": 1629479385299,\n\"duration\": 1,\n\"timestamp\": \"2021-08-20T17:09:45.299Z\"\n},\n\"google-cloud-storage\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479385314,\n\"duration\": 14,\n\"timestamp\": \"2021-08-20T17:09:45.314Z\"\n}\n}\n},\n\"time\": 1629479385506,\n\"duration\": 694,\n\"timestamp\": \"2021-08-20T17:09:45.506Z\"\n},\n\"postgresql\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629479382792,\n\"duration\": 433,\n\"timestamp\": \"2021-08-20T17:09:42.792Z\"\n},\n\"sam\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": {\n\"ok\": true,\n\"systems\": {\n\"GooglePubSub\": {\n\"ok\": true\n},\n\"Database\": {\n\"ok\": true\n},\n\"GoogleGroups\": {\n\"ok\": true\n},\n\"GoogleIam\": {\n\"ok\": true\n},\n\"OpenDJ\": {\n\"ok\": true\n}\n}\n},\n\"time\": 1629479385898,\n\"duration\": 392,\n\"timestamp\": \"2021-08-20T17:09:45.898Z\"\n}\n}\n}"
      val subsystemFuture = callGetStatusFromDropwizardChecks(consentStatusJsonNotOK)
      whenReady(subsystemFuture) { f =>
        assertResult(false)(f.ok)
        assertResult(false)(f.messages.isEmpty)
      }
    }
    "successfully parse Map[String, DropwizardHealth]" in {
      val mapDWStatusJsonNoErrors: String = "{\n\"deadlocks\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629490921326,\n\"duration\": 0,\n\"timestamp\": \"2021-08-20T20:22:01.326Z\"\n},\n\"elastic-search\": {\n\"healthy\": true,\n\"message\": \"ClusterHealth is GREEN\",\n\"error\": null,\n\"details\": null,\n\"time\": 1629490921295,\n\"duration\": 1,\n\"timestamp\": \"2021-08-20T20:22:01.295Z\"\n},\n\"google-cloud-storage\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629490921325,\n\"duration\": 28,\n\"timestamp\": \"2021-08-20T20:22:01.325Z\"\n},\n\"ontology\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629490921359,\n\"duration\": 34,\n\"timestamp\": \"2021-08-20T20:22:01.359Z\"\n},\n\"postgresql\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629490921297,\n\"duration\": 1,\n\"timestamp\": \"2021-08-20T20:22:01.297Z\"\n}\n}"
      val subsystemFuture = callGetStatusFromDropwizardChecks(mapDWStatusJsonNoErrors)
      whenReady(subsystemFuture) { f =>
        assertResult(true)(f.ok)
        assertResult(true)(f.messages.isEmpty)
      }
    }
    "successfully parse Map[String, DropwizardHealth] with errors" in {
      val mapDWStatusJsonSomeErrors: String = "{\n\"deadlocks\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629490921326,\n\"duration\": 0,\n\"timestamp\": \"2021-08-20T20:22:01.326Z\"\n},\n\"elastic-search\": {\n\"healthy\": false,\n\"message\": \"ClusterHealth is RED\",\n\"error\": null,\n\"details\": null,\n\"time\": 1629490921295,\n\"duration\": 1,\n\"timestamp\": \"2021-08-20T20:22:01.295Z\"\n},\n\"google-cloud-storage\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629490921325,\n\"duration\": 28,\n\"timestamp\": \"2021-08-20T20:22:01.325Z\"\n},\n\"ontology\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629490921359,\n\"duration\": 34,\n\"timestamp\": \"2021-08-20T20:22:01.359Z\"\n},\n\"postgresql\": {\n\"healthy\": true,\n\"message\": null,\n\"error\": null,\n\"details\": null,\n\"time\": 1629490921297,\n\"duration\": 1,\n\"timestamp\": \"2021-08-20T20:22:01.297Z\"\n}\n}"
      val subsystemFuture = callGetStatusFromDropwizardChecks(mapDWStatusJsonSomeErrors)
      whenReady(subsystemFuture) { f =>
        assertResult(false)(f.ok)
        assertResult(false)(f.messages.isEmpty)
      }
    }
  }

  private def callGetStatusFromDropwizardChecks(entityContent: String): Future[SubsystemStatus] = {
    val mediaType = MediaTypes.`application/json`
    val response = HttpResponse.apply(
      StatusCodes.OK,
      List(headers.`Content-Type`.apply(mediaType)),
      HttpEntity.apply(entityContent).withContentType(ContentType.apply(mediaType)),
      HttpProtocols.`HTTP/2.0`
    )
    val consentStatus = Future.successful(response)
    getStatusFromDropwizardChecks(consentStatus)
  }
}

