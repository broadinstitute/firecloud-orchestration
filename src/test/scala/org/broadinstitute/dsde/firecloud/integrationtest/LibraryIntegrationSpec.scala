package org.broadinstitute.dsde.firecloud.integrationtest

import ESIntegrationSupport._
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

class LibraryIntegrationSpec extends FreeSpec with Matchers with BeforeAndAfterAll {

  lazy val indexName = itTestIndexName

  override def beforeAll = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
  }

  override def afterAll = {
    searchDAO.deleteIndex()
  }

  "Library integration" - {
    "Elastic Search" - {
      "Index exists" in {
        assert(searchDAO.indexExists())
      }
    }
  }

}
