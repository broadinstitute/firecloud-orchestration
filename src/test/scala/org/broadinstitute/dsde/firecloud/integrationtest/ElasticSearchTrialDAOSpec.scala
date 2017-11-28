package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.{searchDAO, trialDAO}
import org.broadinstitute.dsde.firecloud.model.WorkbenchUserInfo
import org.broadinstitute.dsde.firecloud.model.Trial.TrialProject
import org.broadinstitute.dsde.rawls.model.RawlsBillingProjectName
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

class ElasticSearchTrialDAOSpec extends FreeSpec with Matchers with BeforeAndAfterAll with LazyLogging  {

  override def beforeAll = {
    // using the delete from search dao, because we don't have recreate in trial dao.
    searchDAO.recreateIndex()

    // set up example data
    logger.info("indexing fixtures ...")
    ElasticSearchTrialDAOFixtures.fixtureProjects foreach { project => trialDAO.createProject(project.name)}

    ElasticSearchTrialDAOFixtures.fixtureProjects collect {
      case project if project.verified => trialDAO.verifyProject(project.name, verified=project.verified)
    }
    ElasticSearchTrialDAOFixtures.fixtureProjects collect {
      case project if project.user.nonEmpty => trialDAO.claimProject(project.user.get)
    }
    logger.info("... fixtures indexed.")
  }

  override def afterAll = {
    // using the delete from search dao, because we don't have recreate in trial dao.
    searchDAO.deleteIndex()
  }

  "ElasticSearchTrialDAO" - {
    "createProject" - {
      "should insert a new project" in {
        val name = RawlsBillingProjectName("garlic")
        val actual = trialDAO.createProject(name)
        val expected = TrialProject(name, verified=false, user=None)
        assertResult(expected) { actual }
        val expectedCheck = trialDAO.getProject(name)
        assertResult(expected) { expectedCheck }
      }
      "should throw error when inserting an existing project" in {
        val ex = intercept[FireCloudException] {
          trialDAO.createProject(RawlsBillingProjectName("endive"))
        }
        assert(ex.getMessage == "ElasticSearch request failed")
      }
    }
    "verifyProject" - {
      "should update a project with a new value for verified" in {
        val name=RawlsBillingProjectName("endive")
        trialDAO.verifyProject(name, verified=true)
        val actual1 = trialDAO.getProject(name)
        val expected1 = TrialProject(name, verified=true, user=None)
        assertResult(expected1) { actual1 }
        trialDAO.verifyProject(name, verified=false)
        val actual2 = trialDAO.getProject(name)
        val expected2 = TrialProject(name, verified=false, user=None)
        assertResult(expected2) { actual2 }

      }
      "should throw an error if project is not found" in {
        val ex = intercept[FireCloudException] {
          trialDAO.verifyProject(RawlsBillingProjectName("habanero"), verified=true)
        }
        assert(ex.getMessage == "project habanero not found!")
      }

    }
    "claimProject" - {
      "should claim the first available project by alphabetical order" in {
        val user = WorkbenchUserInfo("789", "me")
        val claimed = trialDAO.claimProject(user)
        val expected = TrialProject(RawlsBillingProjectName("date"), verified=true, user=Some(user))
        assertResult(expected) { claimed }
        val claimCheck = trialDAO.getProject(RawlsBillingProjectName("date"))
        assertResult(expected) { claimCheck }
      }
      "should throw an error when no available/verified projects exist" in {
        // this one should succeed - "fennel" is available
        val user = WorkbenchUserInfo("101010", "me2")
        val claimed = trialDAO.claimProject(user)
        // this one should fail - nothing left
        val ex = intercept[FireCloudException] {
          trialDAO.claimProject(user)
        }
        assert(ex.getMessage == "no available projects")
      }
    }
    "countAvailableProjects" - {
      "should return zero when no projects available" in {
        assertResult(0) { trialDAO.countAvailableProjects }
      }
      "should return accurate count of available projects" in {
        // insert three
        Seq("orange", "pineapple", "quince") foreach { proj => trialDAO.createProject(RawlsBillingProjectName(proj))}
        // verify two
        Seq("orange", "quince") foreach { proj => trialDAO.verifyProject(RawlsBillingProjectName(proj), verified=true)}
        assertResult(2) { trialDAO.countAvailableProjects }
      }
    }
    "projectReport" - {
      "should return an accurate report with only verified/claimed projects" in {
        // unverified/unclaimed projects listed below but commented out for developer clarity
        val expected = Seq(
          // TrialProject(RawlsBillingProjectName("apple"), verified=false, None),
          TrialProject(RawlsBillingProjectName("banana"), verified=true, Some(WorkbenchUserInfo("123", "alice@example.com"))),
          TrialProject(RawlsBillingProjectName("carrot"), verified=true, Some(WorkbenchUserInfo("456", "bob@example.com"))),
          TrialProject(RawlsBillingProjectName("date"), verified=true, Some(WorkbenchUserInfo("789", "me"))),
          // TrialProject(RawlsBillingProjectName("endive"), verified=false, None),
          TrialProject(RawlsBillingProjectName("fennel"), verified=true, Some(WorkbenchUserInfo("101010", "me2")))
          // TrialProject(RawlsBillingProjectName("garlic"), verified=false, None),
          // TrialProject(RawlsBillingProjectName("orange"), verified=true, None),
          // TrialProject(RawlsBillingProjectName("pineapple"), verified=false, None),
          // TrialProject(RawlsBillingProjectName("quince"), verified=true, None)
        )
        val actual = trialDAO.projectReport
        assertResult(expected) { actual }
      }
    }
  }
}

object ElasticSearchTrialDAOFixtures {
  val fixtureProjects: Seq[TrialProject] = Seq(
    TrialProject(RawlsBillingProjectName("apple"), verified=false, None),
    TrialProject(RawlsBillingProjectName("banana"), verified=true, Some(WorkbenchUserInfo("123", "alice@example.com"))),
    TrialProject(RawlsBillingProjectName("carrot"), verified=true, Some(WorkbenchUserInfo("456", "bob@example.com"))),
    TrialProject(RawlsBillingProjectName("date"), verified=true, None),
    TrialProject(RawlsBillingProjectName("endive"), verified=false, None),
    TrialProject(RawlsBillingProjectName("fennel"), verified=true, None)
  )
}


