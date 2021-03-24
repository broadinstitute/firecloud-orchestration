package org.broadinstitute.dsde.firecloud.integrationtest

import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.scalatest.freespec.AnyFreeSpec

class OntologyAutocompleteSpec extends AnyFreeSpec {


  "Ontology Autocompete" - {
    "should match prefixes" in {
      val terms:List[TermResource] = ontologyDAO.autocomplete("bipo")
      val labels = terms.map(_.label)
      // NB: yes, what's in the index is actually "ll", not "II"
      assertResult(Set("bipolar disorder", "bipolar I disorder", "bipolar ll disorder")) { labels.toSet }
    }
    "should return empty list for unknown prefix" in {
      val terms:List[TermResource] = ontologyDAO.autocomplete("Mxyzptlk")
      assertResult(List.empty[TermResource]) { terms }
    }
    "should limit results to 20" in {
      // search for a common prefix
      val terms:List[TermResource] = ontologyDAO.autocomplete("dis")
      assertResult(20) { terms.size }
    }
    "should search in both synonyms and labels" in {
      // search for a common prefix
      val terms:List[TermResource] = ontologyDAO.autocomplete("leukoe")
      val labels = terms.map(_.label)
      val expected = Set(
        // matches in label:
        "acute hemorrhagic leukoencephalitis",
        "hypomyelinating leukoencephalopathy",
        "oral leukoedema",
        "progressive multifocal leukoencephalopathy",

        // matches in a synonym:
        "Krabbe disease",
        "myelophthisic anemia",
        "subacute sclerosing panencephalitis"
      )
      assertResult(expected) { labels.toSet }

    }
  }



}
