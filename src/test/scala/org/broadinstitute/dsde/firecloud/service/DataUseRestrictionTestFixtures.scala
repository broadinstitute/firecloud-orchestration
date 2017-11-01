package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import org.broadinstitute.dsde.rawls.model._
import org.joda.time.DateTime
import spray.json.DefaultJsonProtocol.{jsonFormat13, _}
import spray.json.RootJsonFormat


object DataUseRestrictionTestFixtures {

  case class DataUseRestriction(
    GRU: Boolean = false,
    HMB: Boolean = false,
    DS: Seq[String] = Seq.empty[String],
    NCU: Boolean = false,
    NPU: Boolean = false,
    NDMS: Boolean = false,
    NAGR: Boolean = false,
    NCTRL: Boolean = false,
    `RS-PD`: Boolean = false,
    `RS-G`: Boolean = false,
    `RS-FM`: Boolean = false,
    `RS-M`: Boolean = false,
    `RS-POP`: Seq[String] = Seq.empty[String])

  implicit val impAttributeFormat: AttributeFormat with PlainArrayAttributeListSerializer = new AttributeFormat with PlainArrayAttributeListSerializer
  implicit val impDataUseRestriction: RootJsonFormat[DataUseRestriction] = jsonFormat13(DataUseRestriction)

  // Datasets are named by the code for easier identification in tests
  val booleanCodes: Seq[String] = Seq("GRU", "HMB", "NCU", "NPU", "NDMS", "NCTRL", "RS-PD")
  val booleanDatasets: Seq[Workspace] = booleanCodes.map { code =>
    val attributes = Map(AttributeName.withLibraryNS(code) -> AttributeBoolean(true))
    mkWorkspace(attributes, code)
  }

  val listCodes: Seq[String] = Seq("DS", "RS-POP")
  val listValues: Seq[String] = Seq("TERM-1", "TERM-2")
  val listDatasets: Seq[Workspace] = listCodes.map { code =>
    val attributes = Map(AttributeName.withLibraryNS(code) -> AttributeValueList(listValues.map(AttributeString)))
    mkWorkspace(attributes, code)
  }

  // Gender datasets are named by the gender value for easier identification in tests
  val genderVals: Seq[String] = Seq("Female", "Male", "N/A")
  val genderDatasets: Seq[Workspace] = genderVals.map { gender =>
    val attributes = Map(AttributeName.withLibraryNS("RS-G") -> AttributeString(gender))
    mkWorkspace(attributes, gender)
  }

  // Both gender and 'NAGR' codes are saved as string values in workspace attributes
  val nagrVals: Seq[String] = Seq("Yes", "No", "Unspecified")
  val nagrDatasets: Seq[Workspace] = nagrVals.map { value =>
    val attributes = Map(AttributeName.withLibraryNS("NAGR") -> AttributeString(value))
    mkWorkspace(attributes, value)
  }

  val allDatasets: Seq[Workspace] = booleanDatasets ++ listDatasets ++ genderDatasets ++ nagrDatasets

  def mkWorkspace(attributes: Map[AttributeName, Attribute], wsName: String): Workspace = {
    val testUUID: UUID = UUID.randomUUID()
    val defaultAttributes = attributes ++ Map(
      AttributeName.withDefaultNS("description") -> AttributeString("some description"),
      AttributeName.withDefaultNS("userAttributeOne") -> AttributeString("one"),
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
    Workspace(
      workspaceId=testUUID.toString,
      namespace="testWorkspaceNamespace",
      name=wsName,
      authorizationDomain=Set.empty[ManagedGroupRef],
      isLocked=false,
      createdBy="createdBy",
      createdDate=DateTime.now(),
      lastModified=DateTime.now(),
      attributes=defaultAttributes,
      bucketName="bucketName",
      accessLevels=Map.empty,
      authDomainACLs=Map())
  }

}