package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.elasticsearch.index.query.BoolQueryBuilder

import scala.concurrent.ExecutionContext.Implicits.global

class ESResearchPurposeDAO(ontologyDAO: OntologyDAO) extends ResearchPurposeDAO with ElasticSearchDAOResearchPurposeSupport {
  def researchPurposeFilters(researchPurpose: ResearchPurpose): BoolQueryBuilder =
    researchPurposeFilters(researchPurpose, ontologyDAO)
}
