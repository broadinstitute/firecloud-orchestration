package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.UsTieredPriceItem

/**
 * Created by mbemis on 12/2/16.
 */
class StorageServiceSpec extends BaseServiceSpec with StorageServiceSupport {

  val sampleEgressCharges = UsTieredPriceItem(Map(1024L -> BigDecimal(0.12), 10240L -> BigDecimal(0.11), 92160L -> BigDecimal(0.08))).tiers.toList
  val sampleMissingEgressCharges = UsTieredPriceItem(Map.empty).tiers.toList

  "StorageService" - {
    "should calculate egress costs for single-tier objects" in {
      assertResult(Some(BigDecimal(122.88))) {
        getEgressCost(sampleEgressCharges, BigDecimal(1024), 0)
      }
    }

    "should calculate egress costs for mutli-tier objects" in {
      assertResult(Some(BigDecimal(235.52))) {
        getEgressCost(sampleEgressCharges, BigDecimal(2048), 0)
      }
    }

    "should return None when price list tiers don't exist" in {
      assertResult(None) {
        getEgressCost(sampleMissingEgressCharges, BigDecimal(555), 0)
      }
    }
  }

}
