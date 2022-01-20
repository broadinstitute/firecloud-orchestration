package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.dataaccess.MockOntologyDAO
import org.broadinstitute.dsde.firecloud.model.Document
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.{AttributeName, AttributeValueRawJson, _}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impOntologyESTermParent, impOntologyTermParent, impOntologyTermResource}
import spray.json._
import spray.json.DefaultJsonProtocol._

object OntologySearchTestFixtures {

  val datasetTuples:Seq[(String,String,Int,Option[String])] = Seq(
    ("CSA_9220", "Kombucha vaporware flexitarian chambray bespoke", 111, Some("http://purl.obolibrary.org/obo/DOID_9220")), // central sleep apnea
    ("E_4325", "Af iceland squid cold-pressed", 111, Some("http://purl.obolibrary.org/obo/DOID_4325")), // ebola
    ("L_1240", "3 wolf moon vape try-hard knausgaard", 222, Some("http://purl.obolibrary.org/obo/DOID_1240")), // leukemia
    ("HC_2531", "wolf freegan irony lomo", 222, Some("http://purl.obolibrary.org/obo/DOID_2531")), // hematologic cancer
    ("FASD_0050696", "gastropub tattooed hammock mustache", 333, Some("http://purl.obolibrary.org/obo/DOID_0050696")), // fetal alcohol spectrum disorder
    ("D_4", "Neutra selvage chicharrones, prism taxidermy cray squid", 222, Some("http://purl.obolibrary.org/obo/DOID_4")), // disease
    ("None", "Health of activated charcoal portland", 222, None) // no doid
  )

  val ontologyDAO = new MockOntologyDAO

  val fixtureDocs:Seq[Document] = datasetTuples map {x:(String,String,Int,Option[String]) =>
    val phenoAttrs:AttributeMap = x._4 match {
      case Some(doid) =>
        val term = ontologyDAO.data(doid).head
        val diseaseAttrs = Map(
          AttributeName("library","diseaseOntologyID") -> AttributeString(doid),
          AttributeName("library","diseaseOntologyLabel") -> AttributeString(term.label)
        )
        val parentAttr = term.parents match {
          case Some(parents) =>
            val esparents = parents.map(_.toESTermParent)
            Map(AttributeName.withDefaultNS("parents") -> AttributeValueRawJson(esparents.toJson.compactPrint))
          case _ => Map.empty
        }
        diseaseAttrs ++ parentAttr
      case _ => Map.empty[AttributeName, Attribute]
    }

    Document(x._1, Map(
      AttributeName("library","datasetName") -> AttributeString(x._1),
      AttributeName("library","indication") -> AttributeString(x._2),
      AttributeName("library","numSubjects") -> AttributeNumber(x._3)
    ) ++ phenoAttrs)
  }

}
