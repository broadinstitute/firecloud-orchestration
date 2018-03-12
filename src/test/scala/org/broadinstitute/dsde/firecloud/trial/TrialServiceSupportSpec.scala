package org.broadinstitute.dsde.firecloud.trial

import com.google.api.services.sheets.v4.model.{SpreadsheetProperties, ValueRange}
import org.broadinstitute.dsde.firecloud.dataaccess.{MockThurloeDAO, MockTrialDAO, ThurloeDAO, TrialDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses.Ready
import org.broadinstitute.dsde.firecloud.model.Trial.TrialProject
import org.broadinstitute.dsde.firecloud.model.{UserInfo, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, TrialServiceSupport}
import org.broadinstitute.dsde.rawls.model.RawlsBillingProjectName

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}

class TrialServiceSupportSpec extends BaseServiceSpec with TrialServiceSupport {

  def specTrialDao: TrialDAO = new TrialServiceSupportSpecTrialDAO
  def specThurloeDao: ThurloeDAO = new MockThurloeDAO

  implicit protected val executionContext: ExecutionContext = executor // from RouteTest
  val trialDAO = specTrialDao // to satisfy the TrialServiceSupport trait

  "Trial Service Support" - {

    "should generate spreadsheet properties" in {
      val title: String = "title"
      val props: SpreadsheetProperties = makeSpreadsheetProperties(title)
      props shouldNot be (null)
      props.getTitle should include(title)
    }

    "should generate spreadsheet values" in {
      val managerInfo: UserInfo = UserInfo("token", "subjectId")
      val majorDimension: String = "ROWS"
      val range: String = "Sheet1!A1"
      val values: Future[ValueRange] = makeSpreadsheetValues(managerInfo, specTrialDao, specThurloeDao, majorDimension, range)(executionContext)
      val numRows: Int = specTrialDao.projectReport.size + 1 // +1 for the added header row
      val numCols: Int = 6
      values.map { vals =>
        vals.getMajorDimension should equal(majorDimension)
        vals.getRange should equal(range)
        vals.getValues should have length numRows
        vals.getValues.map { row => row should have length numCols}
      }(executionContext)
    }

  }

  class TrialServiceSupportSpecTrialDAO extends MockTrialDAO {

    override def projectReport: Seq[TrialProject] = {
      Seq("one", "two", "three").map { name =>
        TrialProject(RawlsBillingProjectName(s"project-$name"), verified = true, Some(WorkbenchUserInfo(s"user-$name", s"user-$name@somewhere.org")), Some(Ready))
      }
    }
  }

}
