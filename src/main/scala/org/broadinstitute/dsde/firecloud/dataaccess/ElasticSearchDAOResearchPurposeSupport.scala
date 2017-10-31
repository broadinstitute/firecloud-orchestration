package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionSupport
import org.broadinstitute.dsde.rawls.model.AttributeName
import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.QueryBuilders.{boolQuery, termQuery}

trait ElasticSearchDAOResearchPurposeSupport extends DataUseRestrictionSupport with LazyLogging {

  def researchPurposeFilters(rp: ResearchPurpose): BoolQueryBuilder = {
    val bool = boolQuery

    val durRoot = AttributeName.toDelimitedName(structuredUseRestrictionAttributeName)

    /*
      purpose: NAGR: Aggregate analysis to understand variation in the general population
      dul:     Any dataset where NAGR is false and is (GRU or HMB)
     */
    if (rp.NAGR) {
      bool.must(termQuery(s"$durRoot.NAGR", false))
      bool.must(boolQuery()
        .should(termQuery(s"$durRoot.GRU", true))
        .should(termQuery(s"$durRoot.HMB", true))
      )
    }

    /*
      purpose: NCU:  Commercial purpose/by a commercial entity
      dul:     Any dataset where NPU and NCU are both false
     */
    if (rp.NCU) {
      bool.must(termQuery(s"$durRoot.NPU", false))
      bool.must(termQuery(s"$durRoot.NCU", false))
    }

    /*
      purpose: POA: Study population origins or ancestry
      dul:     Any dataset tagged with GRU
    */
    if (rp.POA)
      bool.must(termQuery(s"$durRoot.GRU", true))


    bool
  }

}
