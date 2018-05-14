package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.elasticsearch.index.query.BoolQueryBuilder

import scala.concurrent.ExecutionContext.Implicits.global

class ESResearchPurposeSupport(ontologyDAO: OntologyDAO) extends ResearchPurposeSupport with ElasticSearchDAOResearchPurposeSupport {
  def researchPurposeFilters(researchPurpose: ResearchPurpose, attributePrefix: Option[String]): BoolQueryBuilder =
    researchPurposeFilters(researchPurpose, ontologyDAO, attributePrefix)
}
