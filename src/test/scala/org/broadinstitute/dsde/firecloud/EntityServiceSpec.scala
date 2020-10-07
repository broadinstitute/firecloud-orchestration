package org.broadinstitute.dsde.firecloud

import java.util.zip.ZipFile

import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.scalatest.BeforeAndAfterEach
import spray.http.StatusCodes

import scala.concurrent.Future

class EntityServiceSpec extends BaseServiceSpec with BeforeAndAfterEach {
  override def beforeEach(): Unit = {
    searchDao.reset
  }

  override def afterEach(): Unit = {
    searchDao.reset
  }

  "EntityClient should extract TSV files out of bagit zips" - {
    "with neither participants nor samples in the zip" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/nothingbag.zip")
      EntityService.unzipTSVs("nothingbag", zip) { (participants, samples) =>
        participants shouldBe None
        samples shouldBe None
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with both participants and samples in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/flat_testbag.zip")
      EntityService.unzipTSVs("flat_testbag", zip) { (participants, samples) =>
        participants.map(_.stripLineEnd) shouldEqual Some("imagine this is a participants.tsv in a flat file structure")
        samples.map(_.stripLineEnd) shouldEqual Some("imagine this is a samples.tsv in a flat file structure")
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with only participants in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/participants_only_flat_testbag.zip")
      EntityService.unzipTSVs("participants_only_flat_testbag", zip) { (participants, samples) =>
        participants.map(_.stripLineEnd) shouldEqual Some("imagine this is a participants.tsv all alone in a flat file structure")
        samples.map(_.stripLineEnd) shouldEqual None
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with only samples in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/samples_only_flat_testbag.zip")
      EntityService.unzipTSVs("samples_only_flat_testbag", zip) { (participants, samples) =>
        participants.map(_.stripLineEnd) shouldEqual None
        samples.map(_.stripLineEnd) shouldEqual Some("imagine this is a samples.tsv all alone in a flat file structure")
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with both participants and samples in a zip with a nested file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/nested_testbag.zip")
      EntityService.unzipTSVs("nested_testbag", zip) { (participants, samples) =>
        participants.map(_.stripLineEnd) shouldEqual Some("imagine this is a participants.tsv in a nested file structure")
        samples.map(_.stripLineEnd) shouldEqual Some("imagine this is a samples.tsv in a nested file structure")
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with both participants and samples and an extra tsv file in a zip with a nested file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/extra_file_nested_testbag.zip")
      EntityService.unzipTSVs("extra_file_nested_testbag", zip) { (participants, samples) =>
        participants.map(_.stripLineEnd) shouldEqual Some("imagine this is a participants.tsv in a nested file structure")
        samples.map(_.stripLineEnd) shouldEqual Some("imagine this is a samples.tsv in a nested file structure")
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with multiple participants in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/duplicate_participants_nested_testbag.zip")

      val ex = intercept[FireCloudExceptionWithErrorReport] {
        EntityService.unzipTSVs("duplicate_participants_nested_testbag", zip) { (participants, samples) =>
          Future.successful(RequestComplete(StatusCodes.OK))
        }
      }
      ex.errorReport.message shouldEqual "More than one participants.tsv file found in bagit duplicate_participants_nested_testbag"
    }

    "with multiple samples in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/duplicate_samples_nested_testbag.zip")

      val ex = intercept[FireCloudExceptionWithErrorReport] {
        EntityService.unzipTSVs("duplicate_samples_nested_testbag", zip) { (participants, samples) =>
          Future.successful(RequestComplete(StatusCodes.OK))
        }
      }
      ex.errorReport.message shouldEqual "More than one samples.tsv file found in bagit duplicate_samples_nested_testbag"
    }
  }
}
