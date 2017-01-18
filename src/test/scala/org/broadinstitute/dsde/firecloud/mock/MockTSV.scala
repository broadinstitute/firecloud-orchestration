package org.broadinstitute.dsde.firecloud.mock

import spray.http._

import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile

/**
 * Created with IntelliJ IDEA.
 * User: hussein
 * Date: 07/28/2015
 * Time: 16:59
 */
object MockTSVStrings {

  /*
   * TSVs for testing pure TSV parsing only.
   * None of these are valid for TSV import.
   */
  val empty = ""
  val onlyNewlines = "\n\n\n\n\n\n"

  val rowTooLong = List(
      List("foo", "bar", "baz").mkString("\t"),
      List("this", "line's", "fine").mkString("\t"),
      List("this", "line's", "too", "long").mkString("\t")
    ).mkString("\n")

  val rowTooShort = List(
    List("foo", "bar", "baz").mkString("\t"),
    List("this", "line's", "fine").mkString("\t"),
    List("too", "short").mkString("\t")
  ).mkString("\n")

  val tooManyTabs = List(
    List("foo", "bar", "baz").mkString("\t"),
    List("this", "line's", "fine").mkString("\t"),
    List("too", "many", "tabs\t").mkString("\t")
  ).mkString("\n")

  val validOneLine = List(
      List("foo", "bar", "baz").mkString("\t"),
      List("woop", "de", "doo").mkString("\t")
    ).mkString("\n")

  val trailingNewlines = validOneLine + "\n\n\n\n"

  val validMultiline = List(
    List("foo", "bar", "baz").mkString("\t"),
    List("woop", "de", "doo").mkString("\t"),
    List("hip", "hip", "hooray").mkString("\t")
  ).mkString("\n")

  /*
   * TSVs for testing the TSV import code.
   */
  val missingTSVType = List(
    List("sample_id", "bar", "baz").mkString("\t"),
    List("woop", "de", "doo").mkString("\t"),
    List("hip", "hip", "hooray").mkString("\t")).mkString("\n")

  val nonexistentTSVType = List(
    List("wobble:sample_id", "bar", "baz").mkString("\t"),
    List("woop", "de", "doo").mkString("\t"),
    List("hip", "hip", "hooray").mkString("\t")).mkString("\n")

  val malformedEntityType = List(
    List("entity:sampleid", "bar", "baz").mkString("\t"),
    List("woop", "de", "doo").mkString("\t"),
    List("hip", "hip", "hooray").mkString("\t")).mkString("\n")

  //membership TSVs
  val membershipUnknownFirstColumnHeader = List(
    List("membership:sampel_id", "bar").mkString("\t"),
    List("woop", "de").mkString("\t"),
    List("hip", "hip").mkString("\t")).mkString("\n")

  val membershipNotCollectionType = List(
    List("membership:sample_id", "bar").mkString("\t"),
    List("woop", "de").mkString("\t"),
    List("hip", "hip").mkString("\t")).mkString("\n")

  val membershipMissingMembersHeader = List( //missing sample_id
    List("membership:sample_set_id").mkString("\t"),
    List("sset_1").mkString("\t"),
    List("sset_2").mkString("\t")).mkString("\n")

  val membershipExtraAttributes = List(
    List("membership:sample_set_id", "sample", "other_attribute").mkString("\t"),
    List("woop", "de", "doo").mkString("\t"),
    List("hip", "hip", "hooray").mkString("\t")).mkString("\n")

  val membershipValid = List(
    List("membership:sample_set_id", "sample").mkString("\t"),
    List("sset_01", "sample_01").mkString("\t"),
    List("sset_01", "sample_02").mkString("\t")).mkString("\n")

  //entity TSVs
  val entityUnknownFirstColumnHeader = List(
    List("entity:sampel_id", "bar", "baz").mkString("\t"),
    List("woop", "de", "doo").mkString("\t"),
    List("hip", "hip", "hooray").mkString("\t")).mkString("\n")

  val entityHasDupes = List(
    List("entity:participant_id", "some_attribute").mkString("\t"),
    List("part_01", "de").mkString("\t"),
    List("part_01", "hip").mkString("\t")).mkString("\n")

  val entityHasCollectionMembers = List(
    List("entity:sample_set_id", "sample").mkString("\t"),
    List("sset_01", "sample_01").mkString("\t"),
    List("sset_01", "sample_02").mkString("\t")).mkString("\n")

  val entityUpdateMissingRequiredAttrs = List( //missing participant
    List("entity:sample_id", "some_attribute").mkString("\t"),
    List("sample_01", "de").mkString("\t"),
    List("sample_02", "hip").mkString("\t")).mkString("\n")

  val entityUpdateWithRequiredAttrs = List(
    List("entity:sample_id", "participant").mkString("\t"),
    List("sample_01", "part_01").mkString("\t"),
    List("sample_02", "part_02").mkString("\t")).mkString("\n")

  val entityUpdateWithRequiredAndOptionalAttrs = List(
    List("entity:sample_id", "participant", "some_attribute").mkString("\t"),
    List("sample_01", "part_01", "foo").mkString("\t"),
    List("sample_02", "part_02", "bar").mkString("\t")).mkString("\n")

  //update TSVs
  val updateUnknownFirstColumnHeader = List(
    List("update:sampel_id", "bar", "baz").mkString("\t"),
    List("woop", "de", "doo").mkString("\t"),
    List("hip", "hip", "hooray").mkString("\t")).mkString("\n")

  val updateHasDupes = List(
    List("update:participant_id", "some_attribute").mkString("\t"),
    List("part_01", "de").mkString("\t"),
    List("part_01", "hip").mkString("\t")).mkString("\n")

  val updateHasCollectionMembers = List(
    List("update:sample_set_id", "sample").mkString("\t"),
    List("sset_01", "sample_01").mkString("\t"),
    List("sset_01", "sample_02").mkString("\t")).mkString("\n")

  val updateMissingRequiredAttrs = List( //missing participant
    List("update:sample_id", "some_attribute").mkString("\t"),
    List("sample_01", "de").mkString("\t"),
    List("sample_02", "hip").mkString("\t")).mkString("\n")

  val updateWithRequiredAttrs = List(
    List("update:sample_id", "participant").mkString("\t"),
    List("sample_01", "part_01").mkString("\t"),
    List("sample_02", "part_02").mkString("\t")).mkString("\n")

  val updateWithRequiredAndOptionalAttrs = List(
    List("update:sample_id", "participant", "some_attribute").mkString("\t"),
    List("sample_01", "part_01", "foo").mkString("\t"),
    List("sample_02", "part_02", "bar").mkString("\t")).mkString("\n")


  val addNewWorkspaceAttributes = List(
        List("workspace:attributeName1", "attributeName2", "attributeName3").mkString("\t"),
        List("\"attributeValue1\"", "true", "800").mkString("\t")).mkString("\n")

  val duplicateKeysWorkspaceAttributes = List(
    List("workspace:a1", "a1").mkString("\t"),
    List("v1", "v2").mkString("\t")).mkString("\n")

  val wrongHeaderWorkspaceAttributes = List(
    List("a3", "a4").mkString("\t"),
    List("v3", "v4").mkString("\t")).mkString("\n")

  val tooManyNamesWorkspaceAttributes = List(
    List("workspace:a5", "a6", "a7").mkString("\t"),
    List("v5", "v6").mkString("\t")).mkString("\n")

  val tooManyValuesWorkspaceAttributes = List(
    List("workspace:a5", "a6").mkString("\t"),
    List("v5", "v6", "v7").mkString("\t")).mkString("\n")

  val tooManyRowsWorkspaceAttributes = List(
    List("workspace:a5", "a6").mkString("\t"),
    List("v5", "v6", "v7").mkString("\t"),
    List("v8", "v9", "v10").mkString("\t")).mkString("\n")

  val tooFewRowsWorkspaceAttributes = List(
    List("workspace:a5", "a6").mkString("\t")).mkString("\n")


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
