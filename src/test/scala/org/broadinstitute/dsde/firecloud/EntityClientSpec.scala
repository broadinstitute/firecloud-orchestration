package org.broadinstitute.dsde.firecloud

import java.util.zip.ZipFile

import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.scalatest.BeforeAndAfterEach

class EntityClientSpec extends BaseServiceSpec with BeforeAndAfterEach {
  override def beforeEach(): Unit = {
    searchDao.reset
  }

  override def afterEach(): Unit = {
    searchDao.reset
  }

  "EntityClient should extract TSV files out of bagit zips" - {
    "with both participants and samples in the zip" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/testbag.zip")
      val (participants, samples) = EntityClient.unzipTSVs(zip)
      participants.map(_.stripLineEnd) shouldEqual Some("imagine this is a participants.tsv")
      samples.map(_.stripLineEnd) shouldEqual Some("imagine this is a samples.tsv")
    }

    "with neither participants nor samples in the zip" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/nothingbag.zip")
      val (participants, samples) = EntityClient.unzipTSVs(zip)
      participants shouldBe None
      samples shouldBe None
    }
  }
}
