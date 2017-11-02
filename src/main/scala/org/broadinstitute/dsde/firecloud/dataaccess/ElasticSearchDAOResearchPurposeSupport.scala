package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.DataUse.{DiseaseOntologyNodeId, ResearchPurpose}
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionSupport
import org.broadinstitute.dsde.rawls.model.AttributeName
import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.QueryBuilders.{boolQuery, termQuery}

trait ElasticSearchDAOResearchPurposeSupport extends DataUseRestrictionSupport with LazyLogging {

  val durRoot = AttributeName.toDelimitedName(structuredUseRestrictionAttributeName)

  def researchPurposeFilters(rp: ResearchPurpose): BoolQueryBuilder = {
    val bool = boolQuery

    /*
      purpose: NAGR: Aggregate analysis to understand variation in the general population
      dul:     Any dataset where NAGR is false and is (GRU or HMB)
     */
    if (rp.NAGR) {
      bool.must(code("NAGR", false))
      bool.must(boolQuery()
        .should(code("GRU", true))
        .should(code("HMB", true))
      )
    }

    /*
      purpose: NCU:  Commercial purpose/by a commercial entity
      dul:     Any dataset where NPU and NCU are both false
     */
    if (rp.NCU) {
      bool.must(code("NPU", false))
      bool.must(code("NCU", false))
    }

    /*
      purpose: POA: Study population origins or ancestry
      dul:     Any dataset tagged with GRU
    */
    if (rp.POA)
      bool.must(code("GRU", true))


    /*
      purpose: DS: Disease focused research
      dul:
                Any dataset with GRU=true
                Any dataset with HMB=true
                Any dataset tagged to this disease exactly
                Any dataset tagged to a DOID ontology Parent of disease X

     */
    if (rp.DS.nonEmpty) {
      val dsClause = generateDiseaseQuery(rp.DS)
      dsClause.should(code("GRU", true))
      dsClause.should(code("HMB", true))
      bool.must(dsClause)
    }

    /*
      purpose: NDMS: Methods development/Validation study
      dul:
                Any dataset where NDMS is false
                Any dataset where NDMS is true AND DS-X match


     */
    if (rp.NDMS) {
      val ndmsClause = boolQuery()
      if (rp.DS.nonEmpty) {
        ndmsClause.should(boolQuery()
          .must(code("NDMS", true))
          .must(generateDiseaseQuery(rp.DS))
        )
      } else {
        ndmsClause.should(code("NDMS", false))
      }
      bool.must(ndmsClause)
    }

    /*
      purpose: NCTRL: Control set
      dul:
                Any dataset where NCTRL is false and is (GRU or HMB)
                Any DS-X match, if user specified a disease in the res purpose search


     */
    if (rp.NCTRL) {
      val nctrlClause = boolQuery()
      nctrlClause.should(boolQuery()
        .must(code("NCTRL", false))
        .must(boolQuery()
          .should(code("GRU", true))
          .should(code("HMB", true))
        )
      )
      if (rp.DS.nonEmpty)
        nctrlClause.should(generateDiseaseQuery(rp.DS))
      bool.must(nctrlClause)
    }

    bool
  }

  private def generateDiseaseQuery(nodeids: Seq[DiseaseOntologyNodeId]): BoolQueryBuilder = {
    // TODO: query to get all parents of the given node, add parents to this query
    val dsClause = boolQuery()
    nodeids foreach { id =>
      dsClause.should(termQuery(s"$durRoot.DS", id.numericId))
    }
    dsClause
  }

  private def code(code: String, value: Boolean) = termQuery(s"$durRoot.$code", value)

}
