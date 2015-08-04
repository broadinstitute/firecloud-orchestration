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
  val unknownFirstColumnHeader = List(
    List("sampel_id", "bar", "baz").mkString("\t"),
    List("woop", "de", "doo").mkString("\t"),
    List("hip", "hip", "hooray").mkString("\t")).mkString("\n")

  val collectionTypeWithMissingMembersHeader = List( //missing sample_id
    List("sample_set_id").mkString("\t"),
    List("sset_1").mkString("\t"),
    List("sset_2").mkString("\t")).mkString("\n")

  val collectionTypeWithExtraAttributes = List(
    List("sample_set_id", "sample_id", "other_attribute").mkString("\t"),
    List("woop", "de", "doo").mkString("\t"),
    List("hip", "hip", "hooray").mkString("\t")).mkString("\n")

  val validCollection = List(
    List("sample_set_id", "sample_id").mkString("\t"),
    List("sset_01", "sample_01").mkString("\t"),
    List("sset_01", "sample_02").mkString("\t")).mkString("\n")

  val dupedEntityUpdate = List(
    List("participant_id", "some_attribute").mkString("\t"),
    List("part_01", "de").mkString("\t"),
    List("part_01", "hip").mkString("\t")).mkString("\n")

  val entityUpdateMissingRequiredAttrs = List( //missing participant_id
    List("sample_id", "some_attribute").mkString("\t"),
    List("sample_01", "de").mkString("\t"),
    List("sample_02", "hip").mkString("\t")).mkString("\n")

  val entityUpdateWithRequiredAttrs = List(
    List("sample_id", "participant_id").mkString("\t"),
    List("sample_01", "part_01").mkString("\t"),
    List("sample_02", "part_02").mkString("\t")).mkString("\n")

  val entityUpdateWithRequiredAndOptionalAttrs = List(
    List("sample_id", "participant_id", "some_attribute").mkString("\t"),
    List("sample_01", "part_01", "foo").mkString("\t"),
    List("sample_02", "part_02", "bar").mkString("\t")).mkString("\n")
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
}

object MockTSVFormData {
  private def wrapInMultipart( fieldName: String, data: String ): MultipartFormData = {
    MultipartFormData( Seq( BodyPart( HttpEntity( ContentType(MediaType.custom("text", "plain")),
      data),
      fieldName)))
  }

  val unknownFirstColumnHeader = wrapInMultipart("entities", MockTSVStrings.unknownFirstColumnHeader)
  val collectionTypeWithMissingMembersHeader = wrapInMultipart("entities", MockTSVStrings.collectionTypeWithMissingMembersHeader)
  val collectionTypeWithExtraAttributes = wrapInMultipart("entities", MockTSVStrings.collectionTypeWithExtraAttributes)
  val validCollection = wrapInMultipart("entities", MockTSVStrings.validCollection)
  val dupedEntityUpdate = wrapInMultipart("entities", MockTSVStrings.dupedEntityUpdate)
  val entityUpdateMissingRequiredAttrs = wrapInMultipart("entities", MockTSVStrings.entityUpdateMissingRequiredAttrs)
  val entityUpdateWithRequiredAttrs = wrapInMultipart("entities", MockTSVStrings.entityUpdateWithRequiredAttrs)
  val entityUpdateWithRequiredAndOptionalAttrs = wrapInMultipart("entities", MockTSVStrings.entityUpdateWithRequiredAndOptionalAttrs)
}
