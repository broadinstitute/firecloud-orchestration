package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.elasticsearch.index.query.BoolQueryBuilder

trait ResearchPurposeSupport {
  /**
    * Build a query filter based on research purpose.
    *
    * @param researchPurpose description of the research purpose to match
    * @param makeAttributeName function to transform an attribute name; e.g. add a prefix to avoid naming collisions with other attributes in the search index
    */
  def researchPurposeFilters(researchPurpose: ResearchPurpose, makeAttributeName: String => String = identity): BoolQueryBuilder
}
