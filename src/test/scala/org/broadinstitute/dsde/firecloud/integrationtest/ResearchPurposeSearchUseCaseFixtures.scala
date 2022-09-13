package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.model.Document
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionSupport
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionTestFixtures.DataUseRestriction
import org.broadinstitute.dsde.rawls.model._
import spray.json._

object ResearchPurposeSearchUseCaseFixtures extends DataUseRestrictionSupport {

  // cases as defined in the doc at
  // https://docs.google.com/a/broadinstitute.org/spreadsheets/d/16XzKpOFCyqRTNy9XHFFPx-Vf4PWFydsWAS7exzx26WM/edit?usp=sharing

  // tuples in this order:
  //   dataset name: used to validate results, reflects the row of the above spreadsheet
  //   project name: used to make this code readable
  //   restriction: used to test research purpose matching
  val datasetTuples:Seq[(Int,String,DataUseRestriction)] = Seq(
    (7,  "GRU",                        DataUseRestriction(GRU=true)),
    (8,  "HMB",                        DataUseRestriction(HMB=true)),
    (9,  "DS-CANCER (DOID:162)",       DataUseRestriction(DS=Seq(162))),
    (10, "DS-BREAST_CANCER",           DataUseRestriction(DS=Seq(1612))),
    (11, "DS-DIABETES, NPU",           DataUseRestriction(DS=Seq(9351), NPU=true)),
    (12, "DS-CANCER (DOID:162), NMDS", DataUseRestriction(DS=Seq(162), NMDS=true)),
    (13, "GRU, NMDS",                  DataUseRestriction(GRU=true, NMDS=true)),
    (14, "HMB, NMDS",                  DataUseRestriction(HMB=true, NMDS=true)),
    (15, "GRU, NPU",                   DataUseRestriction(GRU=true, NPU=true)),
    (16, "HMB, NPU",                   DataUseRestriction(HMB=true, NPU=true)),
    (17, "MESA",                       DataUseRestriction(HMB=true, NPU=true)),
    (18, "WHI",                        DataUseRestriction(HMB=true, NPU=true, IRB=true)),
    (19, "GeneSTAR",                   DataUseRestriction(DS=Seq(1287), IRB=true, NMDS=true, NPU=true)),
    (20, "Diabetes Heart HMB",         DataUseRestriction(HMB=true)),
    (21, "Diabetes Heart DS",          DataUseRestriction(DS=Seq(1287))),
    (22, "GENOA",                      DataUseRestriction(DS=Seq(2349), NPU=true)),
    (23, "COPDGENE",                   DataUseRestriction(HMB=true)),
    (24, "EO-COPD",                    DataUseRestriction(DS=Seq(3083))),
    (25, "Mitchell Amish",             DataUseRestriction(HMB=true, IRB=true, NMDS=true)),
    (26, "FHS",                        DataUseRestriction(HMB=true, IRB=true, NPU=true, NMDS=true)),
    (27, "MGH HMB",                    DataUseRestriction(HMB=true, IRB=true)),
    (28, "MGH DS",                     DataUseRestriction(DS=Seq(60224), IRB=true)),
    (29, "VU - Dawood",                DataUseRestriction(GRU=true, IRB=true)),
    (30, "VU - Ben",                   DataUseRestriction(HMB=true, IRB=true)),
    (31, "HVH HMB",                    DataUseRestriction(HMB=true, IRB=true, NMDS=true)),
    (32, "HVH DS",                     DataUseRestriction(DS=Seq(1287), IRB=true, NMDS=true))
  )

  val fixtureDocs:Seq[Document] = datasetTuples map {x:(Int,String,DataUseRestriction) =>
    Document(x._1.toString, Map(
      AttributeName("library","datasetName") -> AttributeString(x._1.toString),
      AttributeName("library","projectName") -> AttributeString(x._2),
      structuredUseRestrictionAttributeName -> AttributeValueRawJson(x._3.toJson)
    ))
  }



}
