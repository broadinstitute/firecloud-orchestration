package org.broadinstitute.dsde.firecloud.service

import akka.actor._
import akka.pattern._
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.RequestCompleteWithErrorReport
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.http.{HttpHeaders, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.util.{Failure, Success, Try}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Created by mbemis on 11/28/16.
 */
object StorageService {
  sealed trait StorageServiceMessage
  case class GetObjectStats(bucketName: String, objectName: String) extends StorageServiceMessage

  def props(storageServiceConstructor: UserInfo => StorageService, userInfo: UserInfo): Props = {
    Props(storageServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new StorageService(userInfo, app.googleServicesDAO)
}

class StorageService(protected val argUserInfo: UserInfo, val googleServicesDAO: GoogleServicesDAO)(implicit val executionContext: ExecutionContext) extends Actor with RestJsonClient {

  implicit val system = context.system

  //import system.dispatcher

  val log = Logging(system, getClass)

  implicit val userInfo = argUserInfo

  import StorageService._

  override def receive: Receive = {
    case GetObjectStats(bucketName: String, objectName: String) => getObjectStats(bucketName, objectName, "CP-COMPUTEENGINE-INTERNET-EGRESS-NA-NA") pipeTo sender
  }

  def getObjectStats(bucketName: String, objectName: String, region: String) = {
    requestToObject[JsObject](googleServicesDAO.getObjectStats(bucketName, objectName, userInfo.accessToken.token)) flatMap { googleResponse =>
      googleServicesDAO.fetchPriceList map { googlePrices =>
        val original = googleResponse.getFields("size").head.toString
        val fileSizeGB = BigDecimal(original.replaceAll("\"", "")) / 1024 / 1024 / 1024

        val egressPrice = getEgressClass(googlePrices.prices, fileSizeGB, region)

        val totalCost = fileSizeGB * egressPrice

        println(fileSizeGB)

        println(totalCost)

        RequestComplete(googleResponse.copy(googleResponse.fields ++ Map("estimatedCost" -> JsNumber(totalCost))))
      }
    }
  }

  private def getEgressClass(googlePrices: GooglePrices, fileSizeGB: BigDecimal, region: String) = {
    val thing = region match {
      case "CP-COMPUTEENGINE-INTERNET-EGRESS-NA-NA" => googlePrices.cpComputeengineInternetEgressNA
      case "CP-COMPUTEENGINE-INTERNET-EGRESS-CN-CN" => googlePrices.cpComputeengineInternetEgressCN
      case "CP-COMPUTEENGINE-INTERNET-EGRESS-AU-AU" => googlePrices.cpComputeengineInternetEgressAU
      case "CP-COMPUTEENGINE-INTERNET-EGRESS-APAC-APAC" => googlePrices.cpComputeengineInternetEgressAPAC
    }

    if(fileSizeGB <= 1) thing.tiers("1024")
    else if(fileSizeGB <= 10) thing.tiers("10240")
    else thing.tiers("92160")
  }
}