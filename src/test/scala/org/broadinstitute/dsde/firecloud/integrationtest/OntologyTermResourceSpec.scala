package org.broadinstitute.dsde.firecloud.integrationtest

import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.dataaccess.MockOntologyDAO
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.broadinstitute.dsde.firecloud.model.Ontology.TermParent
import org.scalatest.freespec.AnyFreeSpec

class OntologyTermResourceSpec extends AnyFreeSpec {

  val mockdata = new MockOntologyDAO().data

  "Ontology TermResource lookup" - {
    "should find central sleep apnea" in {
      val id = "http://purl.obolibrary.org/obo/DOID_9220"
      val terms = ontologyDAO.search(id)
      assertResult(getExpected(id)) { Some(terms) }
    }
    "should find sleep apnea" in {
      val id = "http://purl.obolibrary.org/obo/DOID_535"
      val terms = ontologyDAO.search(id)
      assertResult(getExpected(id)) { Some(terms) }
    }
    "should find ebola" in {
      val id = "http://purl.obolibrary.org/obo/DOID_4325"
      val terms = ontologyDAO.search(id)
      assertResult(getExpected(id)) { Some(terms) }
    }
  }

  // the term search returns a slimmer version of parents than are stored
  // in our mock data; strip out the mock data.
  private def getExpected(id: String) = {
    mockdata.get(id) map { terms =>
      terms.map { term =>
        val slimParents:Option[List[TermParent]] = term.parents match {
          case None => None
          case Some(ps) => Some(ps.map { p =>
            p.copy(definition = None, synonyms = None)
          })
        }
        term.copy(parents = slimParents)
      }
    }
  }

}
