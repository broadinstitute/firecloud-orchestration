package org.broadinstitute.dsde.firecloud.utils

import java.io.StringReader
import scala.collection.JavaConverters._
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}

case class TSVLoadFile(
  firstColumnHeader:String, //The first header column, used to determine the type of entities being imported
  headers:Seq[String], //All the headers
  tsvData:Seq[Seq[String]] //List of rows of the TSV, broken out into fields
)

object TSVParser {
  private def makeParser = {
    // Note we're using a CsvParser with a tab delimiter rather a TsvParser.
    // This is because the CSV formatter handles quotations correctly while the TSV formatter doesn't.
    // See https://github.com/uniVocity/univocity-parsers#csv-format
    val settings = new CsvParserSettings
    // Automatically detect what the line separator is (e.g. \n for Unix, \r\n for Windows).
    settings.setLineSeparatorDetectionEnabled(true)
    settings.setMaxCharsPerColumn(16384)
    settings.getFormat.setDelimiter('\t')
    new CsvParser(settings)
  }

  private def parseLine(tsvLine: Array[String], tsvLineNo: Int, nCols: Int): List[String] = {
    if (tsvLine.length != nCols) {
      throw new RuntimeException(s"TSV parsing error in line $tsvLineNo: wrong number of fields")
    }
    tsvLine.toList
  }

  def parse(tsvString: String): TSVLoadFile = {
    val allRows = makeParser.parseAll(new StringReader(tsvString)).asScala
      // The CsvParser returns null for missing fields, however the application expects the
      // empty string. This replaces all nulls with the empty string.
      // someday, consider trying CsvParserSettings.setEmptyValue("") instead of doing this ourselves
      .map(_.map(s => Option(s).getOrElse("")))
      .toList
    allRows match {
      case h :: t =>
        val tsvData = t.zipWithIndex.map { case (line, idx) => parseLine(line, idx, h.length) }
        // for user-friendliness, we are lenient and ignore any lines that are either just a newline,
        // or consist only of delimiters (tabs) but have no data.
        // we implement this by checking if the line is empty or if all values in the line are the empty string.
        // NB: CsvParserSettings.setSkipEmptyLines, setIgnoreTrailingWhitespaces, and setIgnoreLeadingWhitespaces
        // do not help with this use case, so we write our own implementation.
        val validData =  tsvData.collect {
          case hasValues if hasValues.nonEmpty && hasValues.forall(_.nonEmpty) =>
            hasValues
        }
        TSVLoadFile(h.head, h.toList, validData)
      case _ => throw new RuntimeException("TSV parsing error: no header")
    }
  }
}
