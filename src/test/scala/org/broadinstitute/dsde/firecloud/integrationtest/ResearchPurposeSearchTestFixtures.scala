package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.model.Document
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionSupport
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionTestFixtures._
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionTestFixtures.DataUseRestriction
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
    ("one",      "hydrogen", "antiaging",     "the quick brown fox", DataUseRestriction(GRU=true, NCU=true, NCTRL = true)),
    ("two",      "hydrogen", "antialias",     "the quick brown fox", DataUseRestriction(HMB=true, NMDS=true)),
    ("three",    "hydrogen", "antianxiety",   "the quick brown fox", DataUseRestriction(NCU=true, NMDS=true, NCTRL = true)),
    ("four",     "hydrogen", "antibacterial", "the quick brown fox", DataUseRestriction(NAGR=true, NMDS=true, NCTRL = true)),
    ("five",     "hydrogen", "antibiotic",    "the quick brown fox", DataUseRestriction(NPU=true, NMDS=true)),

    ("six",      "helium",   "antibody",      "jumped over the",     DataUseRestriction(GRU=true, NCU=true)),
    ("seven",    "helium",   "antic",         "jumped over the",     DataUseRestriction(HMB=true, NMDS=true, NCTRL = true)),
    ("eight",    "helium",   "anticavity",    "jumped over the",     DataUseRestriction(NCU=true, NMDS=true, NCTRL = true)),
    ("nine",     "helium",   "anticipate",    "jumped over the",     DataUseRestriction(NAGR=true, NMDS=true, NCTRL = true)),
    ("ten",      "helium",   "anticlimactic", "jumped over the",     DataUseRestriction(NPU=true, NMDS=true)),

    ("eleven",   "lithium",  "anticoagulant",                "lazy dog", DataUseRestriction(GRU=true, NCU=true, NCTRL = true)),
    ("twelve",   "lithium",  "anticorruption",               "lazy dog", DataUseRestriction(HMB=true, NMDS=true)),
    ("thirteen", "lithium",  "antidepressant",               "lazy dog", DataUseRestriction(NCU=true, NMDS=true, NCTRL = true)),
    ("fourteen", "lithium",  "antidisestablishmentarianism", "lazy dog", DataUseRestriction(NAGR=true, NMDS=true, NCTRL = true)),
    ("fifteen",  "lithium",  "antidote",                     "lazy dog", DataUseRestriction(NPU=true, NMDS=true)),

    ("sixteen",   "beryllium",  "antiegalitarian", "sphinx of", DataUseRestriction(NCU=true, DS=Seq(535))), // sleep disorder, parent of central sleep apnea
    ("seventeen", "beryllium",  "antielectron",    "sphinx of", DataUseRestriction(NCU=true, NMDS=true, DS=Seq(9220))), // central sleep apnea
    ("eighteen",  "beryllium",  "antielitism",     "sphinx of", DataUseRestriction(NCU=true, NMDS=true, DS=Seq(535,4325))), // sleep disorder and Ebola hemorrhagic fever
    ("nineteen",  "beryllium",  "antiepileptic",   "sphinx of", DataUseRestriction(NCU=true, NMDS=true, DS=Seq(4325))), // Ebola hemorrhagic fever
    ("twenty",    "beryllium",  "antifashion",     "sphinx of", DataUseRestriction(NCU=true, DS=Seq(9220,4325))) // central sleep apnea and Ebola hemorrhagic fever
  )

  /*
  Sphinx of black quartz, judge my vow.
  Five quacking zephyrs jolt my wax bed
  The five boxing wizards jump quickly
  Jinxed wizards pluck ivy from the big quilt
   */

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
