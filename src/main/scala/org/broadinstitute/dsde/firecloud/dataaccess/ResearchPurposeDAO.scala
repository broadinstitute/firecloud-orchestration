package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.elasticsearch.index.query.BoolQueryBuilder

trait ResearchPurposeDAO {
  def researchPurposeFilters(researchPurpose: ResearchPurpose): BoolQueryBuilder
}
