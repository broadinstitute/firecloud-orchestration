package org.broadinstitute.dsde.firecloud.service

import akka.actor._
import akka.pattern._
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.PerRequest._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.concurrent.ExecutionContext

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
  val log = Logging(system, getClass)
  implicit val userInfo = argUserInfo
  import StorageService._

  override def receive: Receive = {
    case GetObjectStats(bucketName: String, objectName: String) => getObjectStats(bucketName, objectName) pipeTo sender
  }

  def getObjectStats(bucketName: String, objectName: String) = {
    requestToObject[JsObject](googleServicesDAO.getObjectMetadata(bucketName, objectName, userInfo.accessToken.token)) flatMap { googleResponse =>
      googleServicesDAO.fetchPriceList map { googlePrices =>
        val unformattedSize = googleResponse.getFields("size").head.toString
        val fileSizeGB = BigDecimal(unformattedSize.replaceAll("\"", "")) / Math.pow(1000, 3)
        val egressPrice = getEgressCost(googlePrices.prices.cpComputeengineInternetEgressNA.tiers, fileSizeGB, 0)

        RequestComplete(StatusCodes.OK, googleResponse.copy(googleResponse.fields ++ Map("estimatedCostUSD" -> JsNumber(egressPrice))))
      }
    }
  }

  private def getEgressCost(googlePrices: Map[Long, BigDecimal], fileSizeGB: BigDecimal, totalCost: BigDecimal): BigDecimal = {
    if (fileSizeGB <= 0) totalCost
    else if(googlePrices.size <= 1) (googlePrices.head._1 * fileSizeGB) + totalCost
    else {
      val (sizeCharged, sizeRemaining) = if(fileSizeGB <= googlePrices.head._1) (fileSizeGB, BigDecimal(0))
        else (BigDecimal(googlePrices.head._1), fileSizeGB - googlePrices.head._1)
      getEgressCost(googlePrices.tail, sizeRemaining, (sizeCharged * googlePrices.head._2) + totalCost)
    }
  }
}
