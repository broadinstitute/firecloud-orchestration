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

  // tuples in this order:
  //   dataset name: used to validate results
  //   project name: used to test facet filters
  //   indication: used to test suggestions
  //   datasetCustodian: used to test text search
  //   restriction: used to test research purpose matching
  val datasetTuples:Seq[(String,String,String,String,Restriction)] = Seq(
    ("one",      "hydrogen", "antiaging",     "the quick brown fox", Restriction(GRU=true, NCU=true)),
    ("two",      "hydrogen", "antialias",     "the quick brown fox", Restriction(HMB=true)),
    ("three",    "hydrogen", "antianxiety",   "the quick brown fox", Restriction(NCU=true)),
    ("four",     "hydrogen", "antibacterial", "the quick brown fox", Restriction(NAGR=true)),
    ("five",     "hydrogen", "antibiotic",    "the quick brown fox", Restriction(NPU=true)),

    ("six",      "helium",   "antibody",      "jumped over the",     Restriction(GRU=true, NCU=true)),
    ("seven",    "helium",   "antic",         "jumped over the",     Restriction(HMB=true)),
    ("eight",    "helium",   "anticavity",    "jumped over the",     Restriction(NCU=true)),
    ("nine",     "helium",   "anticipate",    "jumped over the",     Restriction(NAGR=true)),
    ("ten",      "helium",   "anticlimactic", "jumped over the",     Restriction(NPU=true)),

    ("eleven",   "lithium",  "anticoagulant",                "lazy dog", Restriction(GRU=true, NCU=true)),
    ("twelve",   "lithium",  "anticorruption",               "lazy dog", Restriction(HMB=true)),
    ("thirteen", "lithium",  "antidepressant",               "lazy dog", Restriction(NCU=true)),
    ("fourteen", "lithium",  "antidisestablishmentarianism", "lazy dog", Restriction(NAGR=true)),
    ("fifteen",  "lithium",  "antidote",                     "lazy dog", Restriction(NPU=true))

  )

  val fixtureDocs:Seq[Document] = datasetTuples map {x:(String,String,String,String,Restriction) =>
    Document(x._1, Map(
      AttributeName("library","datasetName") -> AttributeString(x._1),
      AttributeName("library","projectName") -> AttributeString(x._2),
      AttributeName("library","indication") -> AttributeString(x._3),
      AttributeName("library","datasetCustodian") -> AttributeString(x._4),
      AttributeName.withDefaultNS("dataUseRestriction") -> AttributeValueRawJson(x._5.toJson)
    ))
  }

}
