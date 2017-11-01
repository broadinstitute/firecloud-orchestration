package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.model.Document
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionSupport
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionTestFixture.{DataUseRestriction, impDataUseRestriction}
import org.broadinstitute.dsde.rawls.model._
import spray.json._

object ResearchPurposeSearchTestFixtures extends DataUseRestrictionSupport {

  // tuples in this order:
  //   dataset name: used to validate results
  //   project name: used to test facet filters
  //   indication: used to test suggestions
  //   datasetCustodian: used to test text search
  //   restriction: used to test research purpose matching
  val datasetTuples:Seq[(String,String,String,String,DataUseRestriction)] = Seq(
    ("one",      "hydrogen", "antiaging",     "the quick brown fox", DataUseRestriction(GRU=true, NCU=true)),
    ("two",      "hydrogen", "antialias",     "the quick brown fox", DataUseRestriction(HMB=true)),
    ("three",    "hydrogen", "antianxiety",   "the quick brown fox", DataUseRestriction(NCU=true)),
    ("four",     "hydrogen", "antibacterial", "the quick brown fox", DataUseRestriction(NAGR=true)),
    ("five",     "hydrogen", "antibiotic",    "the quick brown fox", DataUseRestriction(NPU=true)),

    ("six",      "helium",   "antibody",      "jumped over the",     DataUseRestriction(GRU=true, NCU=true)),
    ("seven",    "helium",   "antic",         "jumped over the",     DataUseRestriction(HMB=true)),
    ("eight",    "helium",   "anticavity",    "jumped over the",     DataUseRestriction(NCU=true)),
    ("nine",     "helium",   "anticipate",    "jumped over the",     DataUseRestriction(NAGR=true)),
    ("ten",      "helium",   "anticlimactic", "jumped over the",     DataUseRestriction(NPU=true)),

    ("eleven",   "lithium",  "anticoagulant",                "lazy dog", DataUseRestriction(GRU=true, NCU=true)),
    ("twelve",   "lithium",  "anticorruption",               "lazy dog", DataUseRestriction(HMB=true)),
    ("thirteen", "lithium",  "antidepressant",               "lazy dog", DataUseRestriction(NCU=true)),
    ("fourteen", "lithium",  "antidisestablishmentarianism", "lazy dog", DataUseRestriction(NAGR=true)),
    ("fifteen",  "lithium",  "antidote",                     "lazy dog", DataUseRestriction(NPU=true))

  )

  val fixtureDocs:Seq[Document] = datasetTuples map {x:(String,String,String,String,DataUseRestriction) =>
    Document(x._1, Map(
      AttributeName("library","datasetName") -> AttributeString(x._1),
      AttributeName("library","projectName") -> AttributeString(x._2),
      AttributeName("library","indication") -> AttributeString(x._3),
      AttributeName("library","datasetCustodian") -> AttributeString(x._4),
      structuredUseRestrictionAttributeName -> AttributeValueRawJson(x._5.toJson)
    ))
  }

}
