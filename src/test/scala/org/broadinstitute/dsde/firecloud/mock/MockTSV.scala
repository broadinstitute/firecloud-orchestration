package org.broadinstitute.dsde.firecloud.mock

import spray.http._

import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile

object MockTSVStrings {

  /*
   * Utilities for generating test data.
   */
  private implicit class TSVListSupport(elems: List[String]) {
    def tabbed: String = elems.mkString("\t")
    def newlineSeparated: String = elems.mkString("\n")
    def windowsNewlineSeparated: String = elems.mkString("\r\n")
  }

  private implicit class TSVStringSupport(str: String) {
    def quoted: String = s""""$str""""
  }

  /*
   * TSVs for testing pure TSV parsing only.
   * None of these are valid for TSV import.
   */
  val empty = ""
  val onlyNewlines = "\n\n\n\n\n\n"

  val rowTooLong = List(
      List("foo", "bar", "baz").tabbed,
      List("this", "line's", "fine").tabbed,
      List("this", "line's", "too", "long").tabbed
    ).newlineSeparated

  val rowTooShort = List(
    List("foo", "bar", "baz").tabbed,
    List("this", "line's", "fine").tabbed,
    List("too", "short").tabbed
  ).newlineSeparated

  val tooManyTabs = List(
    List("foo", "bar", "baz").tabbed,
    List("this", "line's", "fine").tabbed,
    List("too", "many", "tabs\t").tabbed
  ).newlineSeparated

  val validOneLine = List(
      List("foo", "bar", "baz").tabbed,
      List("woop", "de", "doo").tabbed
    ).newlineSeparated

  val trailingNewlines = validOneLine + "\n\n\n\n"

  val validMultiline = List(
    List("foo", "bar", "baz").tabbed,
    List("woop", "de", "doo").tabbed,
    List("hip", "hip", "hooray").tabbed
  ).newlineSeparated

  /*
   * TSVs for testing the TSV import code.
   */
  val missingTSVType = List(
    List("sample_id", "bar", "baz").tabbed,
    List("woop", "de", "doo").tabbed,
    List("hip", "hip", "hooray").tabbed).newlineSeparated

  val nonexistentTSVType = List(
    List("wobble:sample_id", "bar", "baz").tabbed,
    List("woop", "de", "doo").tabbed,
    List("hip", "hip", "hooray").tabbed).newlineSeparated

  val malformedEntityType = List(
    List("entity:sampleid", "bar", "baz").tabbed,
    List("woop", "de", "doo").tabbed,
    List("hip", "hip", "hooray").tabbed).newlineSeparated

  //membership TSVs
  val membershipUnknownFirstColumnHeader = List(
    List("membership:sampel_id", "bar").tabbed,
    List("woop", "de").tabbed,
    List("hip", "hip").tabbed).newlineSeparated

  val membershipNotCollectionType = List(
    List("membership:sample_id", "bar").tabbed,
    List("woop", "de").tabbed,
    List("hip", "hip").tabbed).newlineSeparated

  val membershipMissingMembersHeader = List( //missing sample_id
    List("membership:sample_set_id").tabbed,
    List("sset_1").tabbed,
    List("sset_2").tabbed).newlineSeparated

  val membershipExtraAttributes = List(
    List("membership:sample_set_id", "sample", "other_attribute").tabbed,
    List("woop", "de", "doo").tabbed,
    List("hip", "hip", "hooray").tabbed).newlineSeparated

  val membershipValid = List(
    List("membership:sample_set_id", "sample").tabbed,
    List("sset_01", "sample_01").tabbed,
    List("sset_01", "sample_02").tabbed).newlineSeparated

  //entity TSVs
  val entityUnknownFirstColumnHeader = List(
    List("entity:sampel_id", "bar", "baz").tabbed,
    List("woop", "de", "doo").tabbed,
    List("hip", "hip", "hooray").tabbed).newlineSeparated

  val entityHasDupes = List(
    List("entity:participant_id", "some_attribute").tabbed,
    List("part_01", "de").tabbed,
    List("part_01", "hip").tabbed).newlineSeparated

  val entityHasCollectionMembers = List(
    List("entity:sample_set_id", "sample").tabbed,
    List("sset_01", "sample_01").tabbed,
    List("sset_01", "sample_02").tabbed).newlineSeparated

  val entityUpdateMissingRequiredAttrs = List( //missing participant
    List("entity:sample_id", "some_attribute").tabbed,
    List("sample_01", "de").tabbed,
    List("sample_02", "hip").tabbed).newlineSeparated

  val entityUpdateWithRequiredAttrs = List(
    List("entity:sample_id", "participant").tabbed,
    List("sample_01", "part_01").tabbed,
    List("sample_02", "part_02").tabbed).newlineSeparated

  val entityUpdateWithRequiredAndOptionalAttrs = List(
    List("entity:sample_id", "participant", "some_attribute").tabbed,
    List("sample_01", "part_01", "foo").tabbed,
    List("sample_02", "part_02", "bar").tabbed).newlineSeparated

  //update TSVs
  val updateUnknownFirstColumnHeader = List(
    List("update:sampel_id", "bar", "baz").tabbed,
    List("woop", "de", "doo").tabbed,
    List("hip", "hip", "hooray").tabbed).newlineSeparated

  val updateHasDupes = List(
    List("update:participant_id", "some_attribute").tabbed,
    List("part_01", "de").tabbed,
    List("part_01", "hip").tabbed).newlineSeparated

  val updateHasCollectionMembers = List(
    List("update:sample_set_id", "sample").tabbed,
    List("sset_01", "sample_01").tabbed,
    List("sset_01", "sample_02").tabbed).newlineSeparated

  val updateMissingRequiredAttrs = List( //missing participant
    List("update:sample_id", "some_attribute").tabbed,
    List("sample_01", "de").tabbed,
    List("sample_02", "hip").tabbed).newlineSeparated

  val updateWithRequiredAttrs = List(
    List("update:sample_id", "participant").tabbed,
    List("sample_01", "part_01").tabbed,
    List("sample_02", "part_02").tabbed).newlineSeparated

  val updateWithRequiredAndOptionalAttrs = List(
    List("update:sample_id", "participant", "some_attribute").tabbed,
    List("sample_01", "part_01", "foo").tabbed,
    List("sample_02", "part_02", "bar").tabbed).newlineSeparated


  val addNewWorkspaceAttributes = List(
        List("workspace:attributeName1", "attributeName2", "attributeName3").tabbed,
        List("\"attributeValue1\"", "true", "800").tabbed).newlineSeparated

  val duplicateKeysWorkspaceAttributes = List(
    List("workspace:a1", "a1").tabbed,
    List("v1", "v2").tabbed).newlineSeparated

  val wrongHeaderWorkspaceAttributes = List(
    List("a3", "a4").tabbed,
    List("v3", "v4").tabbed).newlineSeparated

  val tooManyNamesWorkspaceAttributes = List(
    List("workspace:a5", "a6", "a7").tabbed,
    List("v5", "v6").tabbed).newlineSeparated

  val tooManyValuesWorkspaceAttributes = List(
    List("workspace:a5", "a6").tabbed,
    List("v5", "v6", "v7").tabbed).newlineSeparated

  val tooManyRowsWorkspaceAttributes = List(
    List("workspace:a5", "a6").tabbed,
    List("v5", "v6", "v7").tabbed,
    List("v8", "v9", "v10").tabbed).newlineSeparated

  val tooFewRowsWorkspaceAttributes = List(
    List("workspace:a5", "a6").tabbed).newlineSeparated

  val quotedValues = List(
    List("foo".quoted, "bar".quoted).tabbed,
    List("baz".quoted, "biz".quoted).tabbed
  ).newlineSeparated

  val quotedValuesWithTabs = List(
    List("foo".quoted, "bar".quoted).tabbed,
    List("baz".quoted, List("this", "has", "tabs").tabbed.quoted).tabbed
  ).newlineSeparated

  val windowsNewline = List(
    List("foo", "bar").tabbed,
    List("baz", "biz").tabbed
  ).windowsNewlineSeparated

  val missingFields1 = List(
    List("foo", "bar", "baz").tabbed,
    List("biz", "", "buz").tabbed
  ).newlineSeparated

  val missingFields2 = List(
    List("foo", "bar", "baz").tabbed,
    List("", "", "buz").tabbed,
    List("abc", "123", "").tabbed
  ).newlineSeparated

}

object MockTSVLoadFiles {
  //DON'T replace these with TSVParser.parse their corresponding MockTSVStrings objects...
  //these are used to test the TSVParser!
  val validOneLine = TSVLoadFile("foo",
    Seq("foo", "bar", "baz"),
    Seq(Seq("woop", "de", "doo")))

  val validMultiLine = TSVLoadFile("foo",
    Seq("foo", "bar", "baz"),
    Seq(
      Seq("woop", "de", "doo"),
      Seq("hip", "hip", "hooray")))

  val validWorkspaceAttributes = TSVLoadFile("workspace", Seq("a1", "a2", "a3"), Seq(Seq("v1", "2", "[1,2,3]")))
  val validOneWorkspaceAttribute = TSVLoadFile("workspace", Seq("a1"), Seq(Seq("v1")))
  val validEmptyStrWSAttribute = TSVLoadFile("workspace", Seq("a1"), Seq(Seq("")))
  val validRemoveWSAttribute = TSVLoadFile("workspace", Seq("a1"), Seq(Seq("__DELETE__")))
  val validRemoveAddAttribute = TSVLoadFile("workspace", Seq("a1", "a2"), Seq(Seq("__DELETE__", "v2")))
  val validQuotedValues = TSVLoadFile("foo", Seq("foo", "bar"), Seq(Seq("baz", "biz")))
  val validQuotedValuesWithTabs = TSVLoadFile("foo", Seq("foo", "bar"), Seq(Seq("baz", "this\thas\ttabs")))
  val missingFields1 = TSVLoadFile("foo", Seq("foo", "bar", "baz"), Seq(Seq("biz", "", "buz")))
  val missingFields2 = TSVLoadFile("foo", Seq("foo", "bar", "baz"), Seq(Seq("", "", "buz"), Seq("abc", "123", "")))
}

object MockTSVFormData {
  private def wrapInMultipart( fieldName: String, data: String ): MultipartFormData = {
    MultipartFormData( Seq( BodyPart( HttpEntity( ContentType(MediaType.custom("text", "plain")),
      data),
      fieldName)))
  }

  val missingTSVType = wrapInMultipart("entities", MockTSVStrings.missingTSVType)
  val nonexistentTSVType = wrapInMultipart("entities", MockTSVStrings.nonexistentTSVType)
  val malformedEntityType = wrapInMultipart("entities", MockTSVStrings.malformedEntityType)

  val membershipUnknownFirstColumnHeader = wrapInMultipart("entities", MockTSVStrings.membershipUnknownFirstColumnHeader)
  val membershipNotCollectionType = wrapInMultipart("entities", MockTSVStrings.membershipNotCollectionType)
  val membershipMissingMembersHeader = wrapInMultipart("entities", MockTSVStrings.membershipMissingMembersHeader)
  val membershipExtraAttributes = wrapInMultipart("entities", MockTSVStrings.membershipExtraAttributes)
  val membershipValid = wrapInMultipart("entities", MockTSVStrings.membershipValid)

  val entityUnknownFirstColumnHeader = wrapInMultipart("entities", MockTSVStrings.entityUnknownFirstColumnHeader)
  val entityHasDupes = wrapInMultipart("entities", MockTSVStrings.entityHasDupes)
  val entityHasCollectionMembers = wrapInMultipart("entities", MockTSVStrings.entityHasCollectionMembers)
  val entityUpdateMissingRequiredAttrs = wrapInMultipart("entities", MockTSVStrings.entityUpdateMissingRequiredAttrs)
  val entityUpdateWithRequiredAttrs = wrapInMultipart("entities", MockTSVStrings.entityUpdateWithRequiredAttrs)
  val entityUpdateWithRequiredAndOptionalAttrs = wrapInMultipart("entities", MockTSVStrings.entityUpdateWithRequiredAndOptionalAttrs)

  val updateUnknownFirstColumnHeader = wrapInMultipart("entities", MockTSVStrings.updateUnknownFirstColumnHeader)
  val updateHasDupes = wrapInMultipart("entities", MockTSVStrings.updateHasDupes)
  val updateHasCollectionMembers = wrapInMultipart("entities", MockTSVStrings.updateHasCollectionMembers)
  val updateMissingRequiredAttrs = wrapInMultipart("entities", MockTSVStrings.updateMissingRequiredAttrs)
  val updateWithRequiredAttrs = wrapInMultipart("entities", MockTSVStrings.updateWithRequiredAttrs)
  val updateWithRequiredAndOptionalAttrs = wrapInMultipart("entities", MockTSVStrings.updateWithRequiredAndOptionalAttrs)

  val addNewWorkspaceAttributes = wrapInMultipart("attributes", MockTSVStrings.addNewWorkspaceAttributes)
  val duplicateKeysWorkspaceAttributes = wrapInMultipart("attributes", MockTSVStrings.duplicateKeysWorkspaceAttributes)
  val wrongHeaderWorkspaceAttributes = wrapInMultipart("attributes", MockTSVStrings.wrongHeaderWorkspaceAttributes)
  val tooManyNamesWorkspaceAttributes = wrapInMultipart("attributes", MockTSVStrings.tooManyNamesWorkspaceAttributes)
  val tooManyValuesWorkspaceAttributes = wrapInMultipart("attributes", MockTSVStrings.tooManyValuesWorkspaceAttributes)
  val tooManyRowsWorkspaceAttributes = wrapInMultipart("attributes", MockTSVStrings.tooManyRowsWorkspaceAttributes)
  val tooFewRowsWorkspaceAttributes = wrapInMultipart("attributes", MockTSVStrings.tooFewRowsWorkspaceAttributes)

}
