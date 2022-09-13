package org.broadinstitute.dsde.test

object LibraryData {

  val consentCodes = Map(
    "library:useLimitationOption" -> "questionnaire",
    "library:HMB" -> true,
    "library:GRU" -> false,
    "library:NCU" -> true,
    "library:NPU" -> true,
    "library:NCTRL" -> false,
    "library:NMDS" -> true,
    "library:RS-PD" -> false,
    "library:IRB" -> false,
    "library:RS-G" -> "N/A",
    "library:NAGR" -> "No")

  val metadataBasic = Map(
     "library:datasetName"->"name",
     "library:datasetVersion"->"v1.0",
     "library:datasetDescription"->"desc",
     "library:datasetCustodian"->"cust",
     "library:datasetDepositor"->"depo",
     "library:contactEmail"->"name@example.com",
     "library:datasetOwner"->"owner",
     "library:institute"->Seq("inst","it","ute"),
     "library:indication"->"indic",
     "library:numSubjects"->123,
     "library:projectName"->"proj",
     "library:datatype"->Seq("data","type"),
     "library:dataCategory"->Seq("data","category"),
     "library:dataUseRestriction"->"dur",
     "library:studyDesign"->"study",
     "library:cellType"->"cell",
     "library:requiresExternalApproval"->false,
     "library:useLimitationOption"-> "skip",
     "library:technology"->Seq("is an optional","array attribute"))

  // to set a discoverable by group, you can add the following with your specific group
  // "library:discoverableByGroups"->Seq("all_broad_users")

   val metadataORSP = metadataBasic ++ Map(
      "library:useLimitationOption"->"orsp",
      "library:orsp"->"some orsp")


}
