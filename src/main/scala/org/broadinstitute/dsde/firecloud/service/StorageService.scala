package org.broadinstitute.dsde.firecloud.service

import akka.actor._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.PerRequest._
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * Created by mbemis on 11/28/16.
  */
object StorageService {

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new StorageService(userInfo, app.googleServicesDAO, app.samDAO)
}

class StorageService(protected val argUserInfo: UserInfo, val googleServicesDAO: GoogleServicesDAO, val samDAO: SamDAO)
                    (implicit val executionContext: ExecutionContext) extends StorageServiceSupport with SprayJsonSupport with LazyLogging {
  implicit val userInfo = argUserInfo
  import StorageService._

  val storageScopes: Seq[String] = HttpGoogleServicesDAO.authScopes ++ HttpGoogleServicesDAO.storageReadOnly

  def GetObjectStats(bucketName: String, objectName: String) = getObjectStats(bucketName, objectName)
  def GetDownload(bucketName: String, objectName: String) = getDownload(bucketName, objectName)

  def getObjectStats(bucketName: String, objectName: String) = {
    samDAO.getPetServiceAccountTokenForUser(userInfo, storageScopes) flatMap { petToken =>
      googleServicesDAO.getObjectMetadata(bucketName, objectName, petToken.accessToken.token).zip(googleServicesDAO.fetchPriceList) map { case (objectMetadata, googlePrices) =>
        Try(objectMetadata.size.toLong) match {
          case Failure(_) => RequestComplete(StatusCodes.OK, objectMetadata)
          case Success(size) => {
            //size is in bytes, must convert to gigabytes
            val fileSizeGB = BigDecimal(size) / Math.pow(1000, 3)
            val googlePricesList = googlePrices.prices.cpComputeengineInternetEgressNA.tiers.toList
            val egressPrice = getEgressCost(googlePricesList, fileSizeGB, 0)
            RequestComplete(StatusCodes.OK, objectMetadata.copy(estimatedCostUSD = egressPrice))
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
