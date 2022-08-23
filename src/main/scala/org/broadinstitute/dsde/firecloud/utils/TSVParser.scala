package org.broadinstitute.dsde.firecloud.utils

import java.io.StringReader
import scala.jdk.CollectionConverters._
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}

case class TSVLoadFile(
  firstColumnHeader:String, //The first header column, used to determine the type of entities being imported
  headers:Seq[String], //All the headers
  tsvData:Seq[Seq[String]] //List of rows of the TSV, broken out into fields
)

object TSVParser {
  final val DELIMITER = '\t'

  private def makeParser = {
    // Note we're using a CsvParser with a tab delimiter rather a TsvParser.
    // This is because the CSV formatter handles quotations correctly while the TSV formatter doesn't.
    // See https://github.com/uniVocity/univocity-parsers#csv-format
    val settings = new CsvParserSettings
    // Automatically detect what the line separator is (e.g. \n for Unix, \r\n for Windows).
    settings.setLineSeparatorDetectionEnabled(true)
    settings.setMaxColumns(1024)
    //64 mb in bytes/4 (assumes 4 bytes per character)
    settings.setMaxCharsPerColumn(16777216)
    settings.getFormat.setDelimiter(DELIMITER)
    settings.setErrorContentLength(16384)
    // By default, the CsvParser returns null for missing fields, however the application expects the
    // empty string. These replace all nulls with the empty string.
    settings.setNullValue("")
    settings.setEmptyValue("")
    new CsvParser(settings)
  }

  private def parseLine(tsvLine: Array[String], tsvLineNo: Int, nCols: Int): List[String] = {
    if (tsvLine.length != nCols) {
      throw new RuntimeException(s"TSV parsing error in line $tsvLineNo: wrong number of fields")
    }
    tsvLine.toList
  }

  def parse(tsvString: String): TSVLoadFile = {
    makeParser.parseAll(new StringReader(tsvString)).asScala.toList match {
      case h :: t =>
        val tsvData = t.zipWithIndex.map { case (line, idx) => parseLine(line, idx, h.length) }
        // for user-friendliness, we are lenient and ignore any lines that are either just a newline,
        // or consist only of delimiters (tabs) but have no data.
        // we implement this by checking to see if any of the line's values is non-empty.  If the line
        // consists only of delimiters, all values will be empty.
        // NB: CsvParserSettings.setSkipEmptyLines, setIgnoreTrailingWhitespaces, and setIgnoreLeadingWhitespaces
        // do not help with this use case, so we write our own implementation.
        val validData =  tsvData.collect {
          case hasValues if hasValues.exists(_.nonEmpty) => hasValues
        }
        TSVLoadFile(h.head, h.toList, validData)
      case _ => throw new RuntimeException("TSV parsing error: no header")
    }
  }
}
