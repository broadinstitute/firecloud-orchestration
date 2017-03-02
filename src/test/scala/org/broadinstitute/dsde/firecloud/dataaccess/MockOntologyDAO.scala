package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.Ontology.{TermParent, TermResource}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class MockOntologyDAO extends OntologyDAO {
  val resources = List(TermResource(
    id="http://purl.obolibrary.org/obo/DOID_9220",
    ontology="Disease",
    usable=true,
    label="central sleep apnea",
    definition="A sleep apnea that is characterized by a malfunction of the basic neurological controls for breathing rate and the failure to give the signal to inhale, causing the individual to miss one or more cycles of breathing.",
    synonyms=Some(List("primary central sleep apnea")),
    parents=Some(List(
      TermParent(
        id="http://purl.obolibrary.org/obo/DOID_0050847",
        order=1,
        label="sleep apnea",
        definition="A sleep disorder characterized by repeated cessation and commencing of breathing that repeatedly disrupts sleep.",
        synonyms=None
      ),
      TermParent(
        id="http://purl.obolibrary.org/obo/DOID_535",
        order=2,
        label="sleep disorder",
        definition="A disease of mental health that involves disruption of sleep patterns.",
        synonyms=Some(List("Non-organic sleep disorder"))
      ),
      TermParent(
        id="http://purl.obolibrary.org/obo/DOID_150",
        order=3,
        label="disease of mental health",
        definition="A disease that involves a psychological or behavioral pattern generally associated with subjective distress or disability that occurs in an individual, and which are not a part of normal development or culture.",
        synonyms=None
      ),
      TermParent(
        id="http://purl.obolibrary.org/obo/DOID_4",
        order=4,
        label="disease",
        definition="A disease is a disposition (i) to undergo pathological processes that (ii) exists in an organism because of one or more disorders in that organism.",
        synonyms=None
      )
    ))))

  override def search(term: String): Future[Option[List[TermResource]]] = {
    if (term == "DOID_9220")
      Future(Some(resources))
    else
      Future(None)
  }
}
