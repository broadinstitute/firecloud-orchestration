package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.DUOS.Consent
import org.broadinstitute.dsde.firecloud.model.Ontology.{TermParent, TermResource}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import spray.http.StatusCodes

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class MockOntologyDAO extends OntologyDAO {

  implicit val errorReportSource = ErrorReportSource("Mock DUOS")

  val data = Map(
    // central sleep apnea
    "DOID_9220" -> List(TermResource(
      id="http://purl.obolibrary.org/obo/DOID_9220",
      ontology="Disease",
      usable=true,
      label="central sleep apnea",
      definition=Some("A sleep apnea that is characterized by a malfunction of the basic neurological controls for breathing rate and the failure to give the signal to inhale, causing the individual to miss one or more cycles of breathing."),
      synonyms=Some(List("primary central sleep apnea")),
      parents=Some(List(
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_0050847",
          order=1,
          label="sleep apnea",
          definition=Some("A sleep disorder characterized by repeated cessation and commencing of breathing that repeatedly disrupts sleep.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_535",
          order=2,
          label="sleep disorder",
          definition=Some("A disease of mental health that involves disruption of sleep patterns."),
          synonyms=Some(List("Non-organic sleep disorder"))
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_150",
          order=3,
          label="disease of mental health",
          definition=Some("A disease that involves a psychological or behavioral pattern generally associated with subjective distress or disability that occurs in an individual, and which are not a part of normal development or culture.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_4",
          order=4,
          label="disease",
          definition=Some("A disease is a disposition (i) to undergo pathological processes that (ii) exists in an organism because of one or more disorders in that organism.")
        )
      )))),

    // ebola
    "DOID_4325" -> List(TermResource(
      id="http://purl.obolibrary.org/obo/DOID_4325",
      ontology="Disease",
      usable=true,
      label="Ebola hemorrhagic fever",
      definition=Some("A viral infectious disease that is a hemorrhagic fever, has_material_basis_in Zaire ebolavirus, has_material_basis_in Sudan ebolavirus, has_material_basis_in Cote d'Ivoire ebolavirus, or has_material_basis_in Bundibugyo ebolavirus, which are transmitted_by contact with the body fluids of an infected animal or person, transmitted_by contaminated fomites, or transmitted_by infected medical equipment. The infection has_symptom fever, has_symptom headache, has_symptom joint pain, has_symptom muscle aches, has_symptom sore throat, has_symptom weakness, has_symptom diarrhea, has_symptom vomiting, has_symptom stomach pain, has_symptom rash, has_symptom red eyes, has_symptom hiccups, and has_symptom internal and external bleeding."),
      synonyms=Some(List("Ebola virus disease")),
      parents=Some(List(
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_934",
          order=1,
          label="viral infectious disease",
          definition=Some("A disease by infectious agent that results_in infection, has_material_basis_in Viruses.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_0050117",
          order=2,
          label="disease by infectious agent",
          definition=Some("A disease that is the consequence of the presence of pathogenic microbial agents, including pathogenic viruses, pathogenic bacteria, fungi, protozoa, multicellular parasites, and aberrant proteins known as prions.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_4",
          order=3,
          label="disease",
          definition=Some("A disease is a disposition (i) to undergo pathological processes that (ii) exists in an organism because of one or more disorders in that organism.")
        )
      )))),

    // leukemia
    "DOID_1240" -> List(TermResource(
      id="http://purl.obolibrary.org/obo/DOID_1240",
      ontology="Disease",
      usable=true,
      label="leukemia",
      definition=Some("A cancer that affects the blood or bone marrow characterized by an abnormal proliferation of blood cells."),
      parents=Some(List(
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_2531",
          order=1,
          label="hematologic cancer",
          definition=Some("An immune system cancer located_in the hematological system that is characterized by uncontrolled cellular proliferation in blood, bone marrow and lymph nodes.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_0060083",
          order=2,
          label="immune system cancer",
          definition=Some("An organ system cancer located_in the immune system that is characterized by uncontrolled cellular proliferation in organs of the immune system.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_0050686",
          order=3,
          label="organ system cancer",
          definition=Some("A cancer that is classified based on the organ it starts in.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_162",
          order=4,
          label="cancer",
          definition=Some("A disease of cellular proliferation that is malignant and primary, characterized by uncontrolled cellular proliferation, local cell invasion and metastasis."),
          synonyms=Some(List("primary cancer","malignant tumor ","malignant neoplasm"))
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_14566",
          order=5,
          label="disease of cellular proliferation",
          definition=Some("A disease that is characterized by abnormally rapid cell division.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_4",
          order=6,
          label="disease",
          definition=Some("A disease is a disposition (i) to undergo pathological processes that (ii) exists in an organism because of one or more disorders in that organism.")
        )
      )))),

    // hematologic cancer (first parent of leukemia)
    "DOID_2531" -> List(TermResource(
      id="http://purl.obolibrary.org/obo/DOID_2531",
      ontology="Disease",
      usable=true,
      label="hematologic cancer",
      definition=Some("An immune system cancer located_in the hematological system that is characterized by uncontrolled cellular proliferation in blood, bone marrow and lymph nodes."),
      parents=Some(List(
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_0060083",
          order=1,
          label="immune system cancer",
          definition=Some("An organ system cancer located_in the immune system that is characterized by uncontrolled cellular proliferation in organs of the immune system.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_0050686",
          order=2,
          label="organ system cancer",
          definition=Some("A cancer that is classified based on the organ it starts in.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_162",
          order=3,
          label="cancer",
          definition=Some("A disease of cellular proliferation that is malignant and primary, characterized by uncontrolled cellular proliferation, local cell invasion and metastasis."),
          synonyms=Some(List("primary cancer","malignant tumor ","malignant neoplasm"))
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_14566",
          order=4,
          label="disease of cellular proliferation",
          definition=Some("A disease that is characterized by abnormally rapid cell division.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_4",
          order=5,
          label="disease",
          definition=Some("A disease is a disposition (i) to undergo pathological processes that (ii) exists in an organism because of one or more disorders in that organism.")
        )
      )))),

    // fetal alcohol spectrum disorder (has multiple parents at the same level)
    "DOID_0050696" -> List(TermResource(
      id="http://purl.obolibrary.org/obo/DOID_0050696",
      ontology="Disease",
      usable=true,
      label="fetal alcohol spectrum disorder",
      definition=Some("A specific developmental disorder and physical disorder that is characterized by physical, behavioral and learning birth defects resulting from maternal ingestion of alcohol during pregnancy."),
      parents=Some(List(
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_0080015",
          order=1,
          label="physical disorder",
          definition=Some("A disease that has_material_basis_in a genetic abnormality, error with embryonic development, infection or compromised intrauterine environment.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_0080015",
          order=1,
          label="specific developmental disorder",
          definition=Some("A developmental disorder of mental health that categorizes specific learning disabilities and developmental disorders affecting coordination.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_0060083",
          order=2,
          label="developmental disorder of mental health",
          definition=Some("A disease of mental health that occur during a child's developmental period between birth and age 18 resulting in retarding of the child's psychological or physical development.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_150",
          order=3,
          label="disease of mental health",
          definition=Some("A disease that involves a psychological or behavioral pattern generally associated with subjective distress or disability that occurs in an individual, and which are not a part of normal development or culture.")
        ),
        TermParent(
          id="http://purl.obolibrary.org/obo/DOID_4",
          order=4,
          label="disease",
          definition=Some("A disease is a disposition (i) to undergo pathological processes that (ii) exists in an organism because of one or more disorders in that organism.")
        )
      )))),

    // disease, the root of the ontology tree
    "DOID_4" -> List(TermResource(
      id="http://purl.obolibrary.org/obo/DOID_4",
      ontology="Disease",
      usable=true,
      label="disease",
      definition=Some("A disease is a disposition (i) to undergo pathological processes that (ii) exists in an organism because of one or more disorders in that organism.")
    ))
  )

  override def search(term: String): Future[Option[List[TermResource]]] = Future(data.get(term))

  override def orspIdSearch(userInfo: UserInfo, orspId: String): Future[Option[Consent]] = {
    orspId match {
      case x if x.equals("unapproved") =>
        throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Unapproved"))
      case x if x.equals("missing") =>
        throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "Not Found"))
      case x if x.equals("12345") =>
        Future(Some(Consent(consentId = "consent-id-12345", name = "12345", translatedUseRestriction = Some("Translation"))))
      case _ => Future(None)
    }
  }

}
