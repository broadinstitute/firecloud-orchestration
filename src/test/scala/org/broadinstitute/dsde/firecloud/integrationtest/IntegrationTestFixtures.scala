package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.model.{AttributeName, AttributeString, Document}

/**
  * Created by davidan on 2/22/17.
  */
object IntegrationTestFixtures {

  val namesIndications:Map[String,String] = Map(
    "TCGA_ACC_ControlledAccess" -> "Adrenocortical carcinoma",
    "TCGA_BRCA_ControlledAccess" -> "Breast Invasive Carcinoma",
    "TCGA_BRCA_OpenAccess" -> "Breast Invasive Carcinoma",
    "TCGA_CHOL_ControlledAccess" -> "Cholangiocarcinoma",
    "TCGA_COAD_ControlledAccess" -> "Colon adenocarcinoma",
    "TCGA_COAD_OpenAccess" -> "Colon adenocarcinoma",
    "TCGA_DLBC_ControlledAccess" -> "Lymphoid Neoplasm Diffuse Large B-cell Lymphoma",
    "TCGA_DLBC_OpenAccess" -> "Lymphoid Neoplasm Diffuse Large B-cell Lymphoma",
    "TCGA_GBM_OpenAccess" -> "Glioblastoma multiforme",
    "TCGA_HNSC_ControlledAccess" -> "Head and Neck squamous cell carcinoma",
    "TCGA_HNSC_OpenAccess" -> "Head and Neck squamous cell carcinoma",
    "TCGA_KICH_OpenAccess" -> "Kidney Chromophobe",
    "TCGA_KIRC_ControlledAccess" -> "Kidney Renal Clear Cell Carcinoma",
    "TCGA_KIRC_OpenAccess" -> "Kidney Renal Clear Cell Carcinoma",
    "TCGA_KIRP_ControlledAccess" -> "Kidney Renal Papillary Cell Carcinoma",
    "TCGA_KIRP_OpenAccess" -> "Kidney Renal Papillary Cell Carcinoma",
    "TCGA_LAML_OpenAccess" -> "Acute Myeloid Leukemia",
    "TCGA_LIHC_ControlledAccess" -> "Liver Hepatocellular Carcinoma",
    "TCGA_MESO_OpenAccess" -> "Mesothelioma",
    "TCGA_PAAD_ControlledAccess" -> "Pancreatic adenocarcinoma",
    "TCGA_PAAD_OpenAccess" -> "Pancreatic adenocarcinoma",
    "TCGA_PRAD_ControlledAccess" -> "Prostate adenocarcinoma",
    "TCGA_READ_OpenAccess" -> "Rectum adenocarcinoma",
    "TCGA_SKCM_ControlledAccess" -> "Skin Cutaneous Melanoma",
    "TCGA_THCA_ControlledAccess" -> "Thyroid carcinoma",
    "TCGA_THYM_ControlledAccess" -> "Thymoma",
    "testing123" -> "test indication"
  )

  val fixtureDocs:Seq[Document] = (namesIndications map {x:(String,String) =>
    Document(x._1, Map(
      AttributeName("library","datasetName") -> AttributeString(x._1),
      AttributeName("library","indication") -> AttributeString(x._2)
    ))
  }).toSeq

}
