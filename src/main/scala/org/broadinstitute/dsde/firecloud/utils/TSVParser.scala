package org.broadinstitute.dsde.firecloud.utils

/**
 * Created with IntelliJ IDEA.
 * User: hussein
 * Date: 07/23/2015
 * Time: 14:01
 */

case class TSVLoadFile(
  firstColumnHeader:String, //The first header column, used to determine the type of entities being imported
  headers:Seq[String], //All the headers
  tsvData:Seq[Map[String, String]] //List of rows of the TSV. Each row is a map from header to value.
)

object TSVParser {
  private def parseLine( tsvLine: String, tsvLineNo: Int, headers: Seq[String] ) : Map[String, String] = {
    val fields = tsvLine.split("\t", -1) //note we don't trim the line here in case the last element is deliberately empty
    if( fields.length != headers.length ) {
      throw new RuntimeException("TSV parsing error in line " + tsvLineNo.toString + ": wrong number of fields")
    }
    headers.zip(fields).toMap
  }

  def parse( tsvString : String ) : TSVLoadFile = {
    val tsvIt = scala.io.Source.fromString(tsvString.replaceAll("\n+$","")).getLines()
     if (!tsvIt.hasNext) {
       throw new RuntimeException("TSV parsing error: no header")
     }

    val headers = tsvIt.next().split("\t", -1)
    val tsvData = tsvIt.zipWithIndex.map { case (line, idx) => parseLine( line, idx, headers ) }
    TSVLoadFile(headers.head, headers, tsvData.toList)
  }
}
