package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat
import java.util
import java.util.Date

import com.google.api.services.sheets.v4.model.{SpreadsheetProperties, ValueRange}
import org.broadinstitute.dsde.firecloud.dataaccess.TrialDAO
import org.broadinstitute.dsde.firecloud.model.Trial.TrialProject

trait TrialServiceSupport {

  def makeSpreadsheetProperties(title: String): SpreadsheetProperties = {
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    val dateString = dateFormat.format(new Date())
    val now = System.currentTimeMillis()
    new SpreadsheetProperties().setTitle(s"$title: $dateString")
  }

  // TODO: This will need to query for more user information as well.
  // TODO: Populate the `values` object with real data
  def makeTrialProjectValues(trialDAO: TrialDAO): ValueRange = {
    import scala.collection.JavaConverters._
    val claimedProjects: Seq[TrialProject] = trialDAO.projectReport
    val availableProjects: Seq[TrialProject] = trialDAO.availableProjectReport
    val values: util.List[util.List[AnyRef]] = List(List[AnyRef]("Test").asJava).asJava
    new ValueRange().setMajorDimension("ROWS").setRange("Sheet1!A1").setValues(values)
  }

}
