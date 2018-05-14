package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.QueryBuilders.{boolQuery, termQuery}

class MockResearchPurposeSupport extends ResearchPurposeSupport {
  def researchPurposeFilters(researchPurpose: ResearchPurpose): BoolQueryBuilder = {
    val query = boolQuery
    researchPurpose.DS.foreach { id =>
      query.should(termQuery("structuredUseRestriction.DS", id.numericId))
    }
    query
  }
}
