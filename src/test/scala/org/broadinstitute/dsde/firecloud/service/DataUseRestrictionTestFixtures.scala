package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import org.broadinstitute.dsde.rawls.model._
import org.joda.time.DateTime
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat


object DataUseRestrictionTestFixtures {

  case class DataUseRestriction(
    GRU: Boolean = false,
    HMB: Boolean = false,
    DS: Seq[Int] = Seq.empty[Int],
    NCU: Boolean = false,
    NPU: Boolean = false,
    NMDS: Boolean = false,
    NAGR: Boolean = false,
    NCTRL: Boolean = false,
    `RS-PD`: Boolean = false,
    `RS-G`: Boolean = false,
    `RS-FM`: Boolean = false,
    `RS-M`: Boolean = false,
    IRB: Boolean = false
  )

  implicit val impAttributeFormat: AttributeFormat with PlainArrayAttributeListSerializer = new AttributeFormat with PlainArrayAttributeListSerializer
  implicit val impDataUseRestriction: RootJsonFormat[DataUseRestriction] = jsonFormat13(DataUseRestriction)

  // Datasets are named by the code for easier identification in tests
  val booleanCodes: Seq[String] = Seq("GRU", "HMB", "NCU", "NPU", "NMDS", "NCTRL", "RS-PD", "IRB")
  val booleanDatasets: Seq[WorkspaceDetails] = booleanCodes.map { code =>
    val attributes = Map(AttributeName.withLibraryNS(code) -> AttributeBoolean(true))
    mkWorkspace(attributes, code, s"{${code.replace("-","")}}-unique")
  }

  val listValues: Seq[String] = Seq("TERM-1", "TERM-2")

  val diseaseCodes: Seq[String] = Seq("DS_URL")
  val diseaseURLs: Seq[String] = Seq("http://purl.obolibrary.org/obo/DOID_9220", "http://purl.obolibrary.org/obo/DOID_535")
  val diseaseValuesLabels: Seq[String] = Seq("central sleep apnea", "sleep disorder")
  val diseaseValuesInts: Seq[Int] = Seq(9220, 535)
  val diseaseDatasets: Seq[WorkspaceDetails] = diseaseCodes.map { code =>
    val attributes = Map(
      AttributeName.withLibraryNS(code) -> AttributeValueList(diseaseURLs.map(AttributeString)),
      AttributeName.withLibraryNS("DS") -> AttributeValueList(diseaseValuesLabels.map(AttributeString))
    )
    mkWorkspace(attributes, "DS", s"{${code.replace("-","")}}-unique")
  }

  // Gender datasets are named by the gender value for easier identification in tests
  val genderVals: Seq[(String, String)] = Seq(("Female", "RS-FM"), ("Male", "RS-M"), ("N/A", "N/A"))
  val genderDatasets: Seq[WorkspaceDetails] = genderVals.flatMap { case (gender: String, code: String) =>
    val attributes = Map(AttributeName.withLibraryNS("RS-G") -> AttributeString(gender))
    Seq(mkWorkspace(attributes, gender, code), mkWorkspace(attributes, gender, s"""RSG${gender.replace("/","")}"""))
  }

  // Both gender and 'NAGR' codes are saved as string values in workspace attributes
  val nagrVals: Seq[String] = Seq("Yes", "No", "Unspecified")
  val nagrDatasets: Seq[WorkspaceDetails] = nagrVals.map { value =>
    val attributes = Map(AttributeName.withLibraryNS("NAGR") -> AttributeString(value))
    mkWorkspace(attributes, value, s"NAGR$value")
  }

  val everythingDataset = Seq(mkWorkspace(
    booleanCodes.map(AttributeName.withLibraryNS(_) -> AttributeBoolean(true)).toMap ++
      diseaseCodes.map(AttributeName.withLibraryNS(_) -> AttributeValueList(diseaseURLs.map(AttributeString))).toMap ++
      Map(AttributeName.withLibraryNS("DS") -> AttributeValueList(diseaseValuesLabels.map(AttributeString))) ++
      Map(AttributeName.withLibraryNS("NAGR") -> AttributeString("Yes")) ++
      Map(AttributeName.withLibraryNS("RS-G") -> AttributeString("Female")),
    "EVERYTHING",
    "EVERYTHING")
  )

  val topThreeDataset = Seq(mkWorkspace(
    Seq("GRU", "HMB").map(AttributeName.withLibraryNS(_) -> AttributeBoolean(true)).toMap ++
      diseaseCodes.map(AttributeName.withLibraryNS(_) -> AttributeValueList(diseaseURLs.map(AttributeString))).toMap ++
      Map(AttributeName.withLibraryNS("DS") -> AttributeValueList(diseaseValuesLabels.map(AttributeString))),
    "TOP_THREE",
    "TOP_THREE")
  )

  val allDatasets: Seq[WorkspaceDetails] = booleanDatasets ++ diseaseDatasets ++ genderDatasets ++ nagrDatasets ++ everythingDataset ++ topThreeDataset

  val validDisplayDatasets: Seq[WorkspaceDetails] = booleanDatasets ++ everythingDataset ++ topThreeDataset

  def mkWorkspace(attributes: Map[AttributeName, Attribute], wsName: String, wsDescription: String): WorkspaceDetails = {
    val testUUID: UUID = UUID.randomUUID()
    val defaultAttributes = attributes ++ Map(
      AttributeName.withDefaultNS("description") -> AttributeString(wsDescription),
      AttributeName.withLibraryNS("description") -> AttributeString(wsDescription),
      AttributeName.withDefaultNS("userAttributeTwo") -> AttributeString("two"),
      AttributeName.withLibraryNS("datasetName") -> AttributeString("name"),
      AttributeName.withLibraryNS("datasetVersion") -> AttributeString("v1.0"),
      AttributeName.withLibraryNS("datasetDescription") -> AttributeString("desc"),
      AttributeName.withLibraryNS("datasetCustodian") -> AttributeString("cust"),
      AttributeName.withLibraryNS("datasetDepositor") -> AttributeString("depo"),
      AttributeName.withLibraryNS("contactEmail") -> AttributeString("name@example.com"),
      AttributeName.withLibraryNS("datasetOwner") -> AttributeString("owner"),
      AttributeName.withLibraryNS("institute") -> AttributeValueList(Seq(AttributeString("one"),AttributeString("two"))),
      AttributeName.withLibraryNS("indication") -> AttributeString("indication"),
      AttributeName.withLibraryNS("numSubjects") -> AttributeNumber(123),
      AttributeName.withLibraryNS("projectName") -> AttributeString("projectName"),
      AttributeName.withLibraryNS("datatype") -> AttributeValueList(Seq(AttributeString("one"),AttributeString("two"))),
      AttributeName.withLibraryNS("dataCategory") -> AttributeValueList(Seq(AttributeString("one"),AttributeString("two"))),
      AttributeName.withLibraryNS("dataUseRestriction") -> AttributeString("dur"),
      AttributeName.withLibraryNS("studyDesign") -> AttributeString("study"),
      AttributeName.withLibraryNS("cellType") -> AttributeString("cellType"),
      AttributeName.withLibraryNS("requiresExternalApproval") -> AttributeBoolean(false),
      AttributeName.withLibraryNS("technology") -> AttributeValueList(Seq(AttributeString("one"),AttributeString("two"))),
      AttributeName.withLibraryNS("useLimitationOption") -> AttributeString("questionnaire"),
      AttributeName.withDefaultNS("_discoverableByGroups") -> AttributeValueList(Seq(AttributeString("one"),AttributeString("two")))
    )
    WorkspaceDetails(
      workspaceId=testUUID.toString,
      namespace="testWorkspaceNamespace",
      name=wsName,
      isLocked=false,
      createdBy="createdBy",
      createdDate=DateTime.now(),
      lastModified=DateTime.now(),
      attributes=Some(defaultAttributes),
      bucketName="bucketName",
      workflowCollectionName=Some("wf-collection"),
      authorizationDomain=Some(Set.empty[ManagedGroupRef]),
      workspaceVersion=WorkspaceVersions.V2,
      googleProject = GoogleProjectId("googleProject"),
      googleProjectNumber = Some(GoogleProjectNumber("googleProjectNumber")),
      billingAccount = Some(RawlsBillingAccountName("billingAccount")),
      completedCloneWorkspaceFileTransfer = Option(DateTime.now()),
      workspaceType = None,
      cloudPlatform = None,
      state = WorkspaceState.Ready
    )
  }

}
