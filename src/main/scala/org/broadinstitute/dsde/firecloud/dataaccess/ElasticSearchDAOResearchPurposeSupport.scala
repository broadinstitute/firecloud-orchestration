package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.DataUse.{DiseaseOntologyNodeId, ResearchPurpose}
import org.broadinstitute.dsde.firecloud.model.Ontology.TermParent
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionSupport
import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.QueryBuilders.{boolQuery, termQuery}

import scala.concurrent.ExecutionContext

trait ElasticSearchDAOResearchPurposeSupport extends DataUseRestrictionSupport with LazyLogging {

  def researchPurposeFilters(rp: ResearchPurpose, ontologyDAO: OntologyDAO, makeAttributeName: String => String)(implicit ec: ExecutionContext): BoolQueryBuilder = {

    val durRoot = makeAttributeName(structuredUseRestrictionName)

    def generateDiseaseMatchLogic(rp: ResearchPurpose, ontologyDAO: OntologyDAO)(implicit ec: ExecutionContext): Option[BoolQueryBuilder] = {
      /*
        purpose: DS: Disease focused research
        dul:
                  Any dataset with GRU=true
                  Any dataset with HMB=true
                  Any dataset tagged to this disease exactly
                  Any dataset tagged to a DOID ontology Parent of disease X
       */
      if (rp.DS.nonEmpty) {
        val dsClause = generateDiseaseQuery(rp.DS, ontologyDAO)
        dsClause.should(encode("GRU", true))
        dsClause.should(encode("HMB", true))
        Some(dsClause)
      } else {
        None
      }
    }

    def generateDiseaseQuery(nodeids: Seq[DiseaseOntologyNodeId], ontologyDAO: OntologyDAO)(implicit ec: ExecutionContext): BoolQueryBuilder = {
      val allnodes = augmentWithDiseaseParents(nodeids, ontologyDAO)

      val dsClause = boolQuery()
      allnodes foreach { id =>
        dsClause.should(encode("DS", id.numericId))
      }
      dsClause
    }

    def augmentWithDiseaseParents(nodeids: Seq[DiseaseOntologyNodeId], ontologyDAO: OntologyDAO)(implicit ec: ExecutionContext): Seq[DiseaseOntologyNodeId] = {
      if (nodeids.isEmpty)
        nodeids // return unchanged; no ontology nodes to augment
      else {
        // for all nodes in the research purpose's DS value, query ontology to get their parent nodes
        nodeids map (node => ontologyDAO.search(node.uri.toString)) flatMap { allTermResults =>
          val parentsToAugment:Seq[DiseaseOntologyNodeId] = (allTermResults collect {
            case termWithParents => termWithParents.parents.getOrElse(List.empty[TermParent]).map(parent => DiseaseOntologyNodeId(parent.id))
          }).flatten
          // append the parent node info to the original research purpose
          nodeids ++ parentsToAugment
        }
      }
    }

    def encode[T](code: String, value: T) = termQuery(s"$durRoot.$code", value)

    val bool = boolQuery

    /*
      purpose: NAGR: Aggregate analysis to understand variation in the general population
      dul:     Any dataset where NAGR is false and is (GRU or HMB)
     */
    if (rp.NAGR) {
      bool.must(encode("NAGR", false))
      bool.must(boolQuery()
        .should(encode("GRU", true))
        .should(encode("HMB", true))
      )
    }

    /*
      purpose: NCU:  Commercial purpose/by a commercial entity
      dul:     Any dataset where NPU and NCU are both false
     */
    if (rp.NCU) {
      bool.must(encode("NPU", false))
      bool.must(encode("NCU", false))
    }

    /*
      purpose: POA: Study population origins or ancestry
      dul:     Any dataset tagged with GRU
    */
    if (rp.POA)
      bool.must(encode("GRU", true))


    /*
      purpose: DS: Disease focused research
      dul:
                Any dataset with GRU=true
                Any dataset with HMB=true
                Any dataset tagged to this disease exactly
                Any dataset tagged to a DOID ontology Parent of disease X
     */
    if (rp.DS.nonEmpty) {
      generateDiseaseMatchLogic(rp, ontologyDAO) map { dsClause => bool.must(dsClause) }
    }

    /*
      purpose: NMDS: Methods development/Validation study
      dul:
                Any dataset where NMDS is false
                Any dataset where NMDS is true AND DS-X match
     */
    if (rp.NMDS) {
      val nmdsClause = boolQuery()
      nmdsClause.should(encode("NMDS", false))
      if (rp.DS.nonEmpty) {
        generateDiseaseMatchLogic(rp, ontologyDAO) map { dsClause =>
          nmdsClause.should(boolQuery()
            .must(encode("NMDS", true))
            .must(dsClause)
          )
        }
      }
      bool.must(nmdsClause)
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
        .must(encode("NCTRL", false))
        .must(boolQuery()
          .should(encode("GRU", true))
          .should(encode("HMB", true))
        )
      )
      if (rp.DS.nonEmpty)
        nctrlClause.should(generateDiseaseQuery(rp.DS, ontologyDAO))
      bool.must(nctrlClause)
    }

    bool
  }
}
