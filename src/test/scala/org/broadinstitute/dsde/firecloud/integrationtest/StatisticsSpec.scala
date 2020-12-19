package org.broadinstitute.dsde.firecloud.integrationtest

import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.broadinstitute.dsde.firecloud.model.Document
import org.broadinstitute.dsde.firecloud.model.Metrics.NumSubjects
import org.broadinstitute.dsde.rawls.model.{AttributeName, AttributeNumber, AttributeString}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class StatisticsSpec extends AnyFreeSpec with Matchers with BeforeAndAfterEach with LazyLogging {

  /* Conf file contains one or more workspace namespaces, referenced via FireCloudConfig.Metrics.libraryNamespaces.
     We filter Library datasets to just those namespaces when calculating metrics.
     For tests - using reference.conf - we expect to see "library-ns-1" and "library-ns-2" as our
     target namespaces.
   */
  private val ignoredTuples:Seq[(String,Int)] = Seq(
    ("should-be-ignored", 944),
    ("some-other-namespace", 777),
    ("library-ns-1-2", 888888) // namespace value is really close but not exact!
  )

  private val ns1Tuples:Seq[(String,Int)] = Seq(
    ("library-ns-1", 101),
    ("library-ns-1", 102)
  )

  private val ns2Tuples:Seq[(String,Int)] = Seq(
    ("library-ns-2", 220),
    ("library-ns-2", 225)
  )

  private def documentsFrom(tuples: Seq[(String,Int)]): Seq[Document] = {
    tuples map {
      case (namespace, numSubjects) =>
        Document(s"$namespace::$numSubjects", Map(
          // the query in ElasticSearchDAO filters using 'namespace' and sums on 'numSubjects';
          // these are the only fields that should matter to the query.
          AttributeName.withDefaultNS("namespace") -> AttributeString(namespace),
          AttributeName.withLibraryNS("numSubjects") -> AttributeNumber(numSubjects),
          // index the following fields - which should be ignored by ElasticSearchDAO -
          // to make sure that the query is not confused by the existence of these.
          AttributeName.withLibraryNS("dulvn") -> AttributeNumber(-9999999),
          AttributeName.withDefaultNS("name") -> AttributeString("library-ns-1"),
          AttributeName.withLibraryNS("useLimitationOption") -> AttributeString("library-ns-2")
        )
      )
    }
  }

  override def beforeEach() = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
  }

  override def afterEach() = {
    searchDAO.deleteIndex()
  }

  "SearchDAO.statistics" - {

    "test reference.conf should contain the libraryNamespaces these tests expect" in {
      assertResult(Set("library-ns-1", "library-ns-2")) {
        FireCloudConfig.Metrics.libraryNamespaces.toSet
      }
    }

    "should return 0 when no documents in the index" in {
      assertResult(NumSubjects(0)) {
        searchDAO.statistics
      }
    }

    "should return 0 when only non-target namespaces exist" in {
      searchDAO.bulkIndex(documentsFrom(ignoredTuples), refresh = true)
      assertResult(NumSubjects(0)) {
        searchDAO.statistics
      }
    }

    "should sum across the first target namespace" in {
      searchDAO.bulkIndex(documentsFrom(ns1Tuples), refresh = true)
      assertResult(NumSubjects(203)) {
        searchDAO.statistics
      }
    }

    "should sum across the first target namespace, while ignoring non-targets" in {
      searchDAO.bulkIndex(documentsFrom(ns1Tuples ++ ignoredTuples), refresh = true)
      assertResult(NumSubjects(203)) {
        searchDAO.statistics
      }
    }

    "should sum across a second target namespace" in {
      searchDAO.bulkIndex(documentsFrom(ns2Tuples), refresh = true)
      assertResult(NumSubjects(445)) {
        searchDAO.statistics
      }
    }

    "should sum across a second target namespace, while ignoring non-targets" in {
      searchDAO.bulkIndex(documentsFrom(ns2Tuples ++ ignoredTuples), refresh = true)
      assertResult(NumSubjects(445)) {
        searchDAO.statistics
      }
    }

    "should sum across multiple target namespaces" in {
      searchDAO.bulkIndex(documentsFrom(ns1Tuples ++ ns2Tuples), refresh = true)
      assertResult(NumSubjects(648)) {
        searchDAO.statistics
      }
    }

    "should sum across multiple target namespaces, while ignoring non-targets" in {
      searchDAO.bulkIndex(documentsFrom(ns1Tuples ++ ns2Tuples ++ ignoredTuples), refresh = true)
      assertResult(NumSubjects(648)) {
        searchDAO.statistics
      }
    }
  }
}
