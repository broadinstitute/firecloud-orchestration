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

  private lazy val parser: CsvParser = {
    // Note we're using a CsvParser with a tab delimiter rather a TsvParser.
    // This is because the CSV formatter handles quotations correctly while the TSV formatter doesn't.
    // See https://github.com/uniVocity/univocity-parsers#csv-format
    val settings = new CsvParserSettings
    // Automatically detect what the line separator is (e.g. \n for Unix, \r\n for Windows).
    settings.setLineSeparatorDetectionEnabled(true)
    settings.getFormat().setDelimiter('\t')
    new CsvParser(settings)
  }

  private def parseLine(tsvLine: Array[String], tsvLineNo: Int, nCols: Int): List[String] = {
    if (tsvLine.length != nCols) {
      throw new RuntimeException(s"TSV parsing error in line $tsvLineNo: wrong number of fields")
    }
    tsvLine.toList
  }

  def parse(tsvString: String): TSVLoadFile = {
    val allRows = parser.parseAll(new StringReader(tsvString)).asScala.toList
    allRows match {
      case h :: t =>
        val tsvData = t.zipWithIndex.map { case (line, idx) => parseLine(line, idx, h.length) }
        TSVLoadFile(h.head, h.toList, tsvData)
      case _ => throw new RuntimeException("TSV parsing error: no header")
    }
  }
}
