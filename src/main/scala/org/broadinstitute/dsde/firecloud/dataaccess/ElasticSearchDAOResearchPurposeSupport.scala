package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.QueryBuilders.{boolQuery, termQuery}

trait ElasticSearchDAOResearchPurposeSupport extends LazyLogging {

  def researchPurposeFilters(rp: ResearchPurpose): BoolQueryBuilder = {
    val bool = boolQuery

    /*
      purpose: NAGR: Aggregate analysis to understand variation in the general population
      dul:     Any dataset where NAGR is false and is (GRU or HMB)
     */
    if (rp.NAGR) {
      bool.must(termQuery("dataUseRestriction.NAGR", false))
      bool.must(boolQuery()
        .should(termQuery("dataUseRestriction.GRU", true))
        .should(termQuery("dataUseRestriction.HMB", true))
      )
    }

    /*
      purpose: NCU:  Commercial purpose/by a commercial entity
      dul:     Any dataset where NPU and NCU are both false
     */
    if (rp.NCU) {
      bool.must(termQuery("dataUseRestriction.NPU", false))
      bool.must(termQuery("dataUseRestriction.NCU", false))
    }

    /*
      purpose: POA: Study population origins or ancestry
      dul:     Any dataset tagged with GRU
    */
    if (rp.POA)
      bool.must(termQuery("dataUseRestriction.GRU", true))


    bool
  }

}
