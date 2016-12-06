package org.broadinstitute.dsde.firecloud.service

import scala.annotation.tailrec

/**
 * Created by mbemis on 12/2/16.
 */
trait StorageServiceSupport {

  /* Egress charges are calculated like income tax. The first N GB are charged at the tier 1 rate,
     the next M GB are charged at the tier 2 rate, and so on until a final cost calculation is reached
   */
  @tailrec
  final def getEgressCost(googlePricesList: List[(Long, BigDecimal)], fileSizeGB: BigDecimal, totalCost: BigDecimal): Option[BigDecimal] = {
    val googlePricesListSorted = googlePricesList.sortBy(_._1)
    googlePricesListSorted match {
      case Nil => None
      case (_, cost) :: Nil => Option((cost * fileSizeGB) + totalCost)
      case (tier, cost) :: tail =>
          val (sizeCharged, sizeRemaining) = if(fileSizeGB <= tier) (fileSizeGB, BigDecimal(0))
          else (BigDecimal(tier), fileSizeGB - tier)
        getEgressCost(tail, sizeRemaining, (sizeCharged * cost) + totalCost)
    }
  }

}
