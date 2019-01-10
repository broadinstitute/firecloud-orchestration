package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.model.Document
import org.broadinstitute.dsde.rawls.model.{AttributeName, AttributeNumber, AttributeString, AttributeValueList}

/**
  * Created by davidan on 2/22/17.
  */
object IntegrationTestFixtures {

  val datasetTuples:Seq[(String,String,Int)] = Seq(
    ("TCGA_DLBC_ControlledAccess", "Lymphoid Neoplasm Diffuse Large B-cell Lymphoma", 111),
    ("TCGA_DLBC_OpenAccess", "Lymphoid Neoplasm Diffuse Large B-cell Lymphoma", 111),
    ("TCGA_GBM_OpenAccess", "Glioblastoma multiforme", 222),
    ("TCGA_HNSC_ControlledAccess", "Head and Neck squamous cell carcinoma", 222),
    ("TCGA_HNSC_OpenAccess", "Head and Neck squamous cell carcinoma", 222),
    ("TCGA_KICH_OpenAccess", "Kidney Chromophobe", 222),
    ("TCGA_KIRC_ControlledAccess", "Kidney Renal Clear Cell Carcinoma", 333),
    ("TCGA_KIRC_OpenAccess", "Kidney Renal Clear Cell Carcinoma", 444),
    ("TCGA_KIRP_ControlledAccess", "Kidney Renal Papillary Cell Carcinoma", 333),
    ("TCGA_KIRP_OpenAccess", "Kidney Renal Papillary Cell Carcinoma", 1),
    ("TCGA_LAML_OpenAccess", "Acute Myeloid Leukemia", 5),
    ("TCGA_LIHC_ControlledAccess", "Liver Hepatocellular Carcinoma", 6),
    ("TCGA_MESO_OpenAccess", "Mesothelioma", 9),
    ("TCGA_PAAD_ControlledAccess", "Pancreatic adenocarcinoma", 8),
    ("TCGA_PAAD_OpenAccess", "Pancreatic adenocarcinoma", 4),
    ("TCGA_PRAD_ControlledAccess", "Prostate adenocarcinoma", 7),
    ("TCGA_READ_OpenAccess", "Rectum adenocarcinoma", 21),
    ("TCGA_SKCM_ControlledAccess", "Skin Cutaneous Melanoma", 4455667),
    ("TCGA_THCA_ControlledAccess", "Thyroid carcinoma", 9999),
    ("TCGA_THYM_ControlledAccess", "Thymoma", 998),
    ("testing123", "test indication", 321),
    ("TCGA_ACC_ControlledAccess",  "Adrenocortical carcinoma", 123),
    ("TCGA_BRCA_ControlledAccess", "Breast Invasive Carcinoma", 2),
    ("TCGA_BRCA_OpenAccess", "Breast Invasive Carcinoma", 2),
    ("TCGA_CHOL_ControlledAccess", "Cholangiocarcinoma", 3),
    ("TCGA_COAD_ControlledAccess", "Colon adenocarcinoma", 3),
    ("TCGA_COAD_OpenAccess", "Colon adenocarcinoma", 3),
    ("ZZZ <encodingtest>&foo", "results won't be escaped", 1)
  )

  val fixtureDocs:Seq[Document] = datasetTuples map {
    case (name, phenotype, count) =>
      Document(name, Map(
        AttributeName("library","datasetName") -> AttributeString(name),
        AttributeName("library","indication") -> AttributeString(phenotype),
        AttributeName("library","datasetOwner") -> AttributeString(phenotype),
        AttributeName("library","numSubjects") -> AttributeNumber(count)
      ))
  }

  val fixtureRestrictedDocs:Seq[Document] = datasetTuples map {
    case (name, phenotype, count) =>
      Document(name, Map(
        AttributeName("library","datasetName") -> AttributeString(name),
        AttributeName("library","indication") -> AttributeString(phenotype),
        AttributeName("library","datasetOwner") -> AttributeString(phenotype),
        AttributeName("library","numSubjects") -> AttributeNumber(count),
        AttributeName.withDefaultNS("workspaceId") -> AttributeString(name),
        AttributeName.withDefaultNS("_discoverableByGroups") -> AttributeValueList(Seq(AttributeString("no_one")))
      ))
  }

}
