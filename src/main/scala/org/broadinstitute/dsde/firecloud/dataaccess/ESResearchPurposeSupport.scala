package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.elasticsearch.index.query.BoolQueryBuilder

import scala.concurrent.ExecutionContext.Implicits.global

class ESResearchPurposeSupport(ontologyDAO: OntologyDAO) extends ResearchPurposeSupport with ElasticSearchDAOResearchPurposeSupport {
  override def researchPurposeFilters(researchPurpose: ResearchPurpose, makeAttributeName: String => String): BoolQueryBuilder =
    researchPurposeFilters(researchPurpose, ontologyDAO, makeAttributeName)
}
