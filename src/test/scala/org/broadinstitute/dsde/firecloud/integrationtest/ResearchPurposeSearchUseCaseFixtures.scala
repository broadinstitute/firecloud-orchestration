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
    (16, "HMB, NPU",                   DataUseRestriction(HMB=true, NPU=true))
  )

  val fixtureDocs:Seq[Document] = datasetTuples map {x:(Int,String,DataUseRestriction) =>
    Document(x._1.toString, Map(
      AttributeName("library","datasetName") -> AttributeString(x._1.toString),
      AttributeName("library","projectName") -> AttributeString(x._2),
      structuredUseRestrictionAttributeName -> AttributeValueRawJson(x._3.toJson)
    ))
  }



}
