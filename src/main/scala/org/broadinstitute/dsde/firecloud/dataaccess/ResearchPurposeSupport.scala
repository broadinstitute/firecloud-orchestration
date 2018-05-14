package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.elasticsearch.index.query.BoolQueryBuilder

trait ResearchPurposeSupport {
  def researchPurposeFilters(researchPurpose: ResearchPurpose, attributePrefix: Option[String] = None): BoolQueryBuilder
}
