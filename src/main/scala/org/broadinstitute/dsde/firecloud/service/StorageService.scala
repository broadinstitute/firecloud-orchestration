package org.broadinstitute.dsde.firecloud.service

import akka.actor._
import akka.pattern._
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.PerRequest._
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Created by mbemis on 11/28/16.
 */
object StorageService {
  sealed trait StorageServiceMessage
  case class GetObjectStats(bucketName: String, objectName: String) extends StorageServiceMessage
  case class GetDownload(bucketName: String, objectName: String) extends StorageServiceMessage

  def props(storageServiceConstructor: UserInfo => StorageService, userInfo: UserInfo): Props = {
    Props(storageServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new StorageService(userInfo, app.googleServicesDAO, app.samDAO)
}

class StorageService(protected val argUserInfo: UserInfo, val googleServicesDAO: GoogleServicesDAO, val samDAO: SamDAO)(implicit val executionContext: ExecutionContext) extends Actor with StorageServiceSupport {
  implicit val system = context.system
  val log = Logging(system, getClass)
  implicit val userInfo = argUserInfo
  import StorageService._

  val storageScopes: Seq[String] = HttpGoogleServicesDAO.authScopes ++ HttpGoogleServicesDAO.storageReadOnly

  override def receive: Receive = {
    case GetObjectStats(bucketName: String, objectName: String) => getObjectStats(bucketName, objectName) pipeTo sender
    case GetDownload(bucketName: String, objectName: String) => getDownload(bucketName, objectName) pipeTo sender
  }

  def getObjectStats(bucketName: String, objectName: String) = {
    samDAO.getPetServiceAccountTokenForUser(userInfo, storageScopes) flatMap { petToken =>
      googleServicesDAO.getObjectMetadata(bucketName, objectName, petToken.accessToken.token).zip(googleServicesDAO.fetchPriceList) map { case (objectMetadata, googlePrices) =>
        Try(objectMetadata.size.toLong).toOption match {
          case None => RequestComplete(StatusCodes.OK, objectMetadata)
          case Some(size) => {
            //size is in bytes, must convert to gigabytes
            val fileSizeGB = BigDecimal(size) / Math.pow(1000, 3)
            val googlePricesList = googlePrices.prices.cpComputeengineInternetEgressNA.tiers.toList
            val egressPrice = getEgressCost(googlePricesList, fileSizeGB, 0)
            RequestComplete(StatusCodes.OK, (objectMetadata.copy(estimatedCostUSD = egressPrice)))
          }
        }
      }
    }
  }

  def getDownload(bucketName: String, objectName: String): Future[PerRequestMessage] = {
    samDAO.getPetServiceAccountTokenForUser(userInfo, storageScopes) flatMap { petToken =>
      googleServicesDAO.getDownload(bucketName, objectName, petToken)
    }
  }

}
