package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.scalatest.FreeSpec

class OntologyAutocompleteSpec extends FreeSpec {


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
        "COL4A1-related familial vascular leukoencephalopathy",
        "hypomyelinating leukoencephalopathy",
        "leukoencephalopathy with vanishing white matter",
        "oral leukoedema",
        "progressive multifocal leukoencephalopathy",

        // matches in a synonym:
        "CADASIL 1",
        "CADASIL 2",
        "CADASIL",
        "hypomyelinating leukodystrophy 7 with or without oligodontia and-or hypogonadotropic hypogonadism", // note label is "leuko" without the "e"
        "Krabbe disease",
        "myelophthisic anemia",
        "Nasu-Hakola disease",
        "subacute sclerosing panencephalitis"

        // following should not match; consent-ontology service matches in their definitions; orch does not
        // "hypomyelinating leukodystrophy 4",
        // "mitochondrial complex I deficiency",
      )
      assertResult(expected) { labels.toSet }

    }
  }



}
