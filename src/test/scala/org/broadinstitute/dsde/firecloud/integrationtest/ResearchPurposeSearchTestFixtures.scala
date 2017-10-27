package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.model.Document
import org.broadinstitute.dsde.rawls.model._
import spray.json.DefaultJsonProtocol._
import spray.json._

object ResearchPurposeSearchTestFixtures {

  case class Restriction(
    GRU: Boolean = false,
    HMB: Boolean = false,
    NCU: Boolean = false,
    NAGR: Boolean = false,
    NPU: Boolean = false
    )

  implicit val impRestrictionFormat = jsonFormat5(Restriction)

  val datasetTuples:Seq[(String,String,Restriction)] = Seq(
    ("CSA_9220", "Kombucha vaporware flexitarian chambray bespoke", Restriction(GRU=true)),
    ("L_1240", "3 wolf moon vape try-hard knausgaard", Restriction(HMB=true)),
    ("HC_2531", "wolf freegan irony lomo", Restriction(NCU=true)),
    ("FASD_0050696", "gastropub tattooed hammock mustache", Restriction(NAGR=true)),
    ("D_4", "Neutra selvage chicharrones, prism taxidermy cray squid", Restriction(NPU=true))
  )

  val fixtureDocs:Seq[Document] = datasetTuples map {x:(String,String,Restriction) =>
    Document(x._1, Map(
      AttributeName("library","projectName") -> AttributeString(x._1),
      AttributeName("library","datasetName") -> AttributeString(x._1),
      AttributeName("library","indication") -> AttributeString(x._2),
      AttributeName.withDefaultNS("dataUseRestriction") -> AttributeValueRawJson(x._3.toJson)
    ))
  }

}
