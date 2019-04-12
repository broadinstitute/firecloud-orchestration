package org.broadinstitute.dsde.test.api.orch

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, UserPool}
import org.broadinstitute.dsde.workbench.fixture.{BillingFixtures, GroupFixtures, WorkspaceFixtures}
import org.broadinstitute.dsde.workbench.service.BillingProject.BillingProjectRole
import org.broadinstitute.dsde.workbench.service.{AclEntry, Orchestration, Rawls, WorkspaceAccessLevel}
import org.scalatest.{FreeSpec, Matchers}
import spray.json.{DefaultJsonProtocol, JsValue, JsonParser}

import scala.util.Try


class AuthDomainMultiGroupsApiSpec extends FreeSpec with Matchers with WorkspaceFixtures with BillingFixtures with GroupFixtures {

  /*
   * Unless otherwise declared, this auth token will be used for API calls.
   * We are using a curator to prevent collisions with users in tests (who are Students and AuthDomainUsers), not
   *  because we specifically need a curator.
   */
  val defaultUser: Credentials = UserPool.chooseCurator
  val authTokenDefault: AuthToken = defaultUser.makeAuthToken()


  "A workspace with an authorization domain" - {
    "with multiple groups inside of it" - {

      "can be created" in {

        val user = UserPool.chooseAuthDomainUser
        implicit val authToken: AuthToken = authTokenDefault

        withGroup("GroupsApiSpec_groupOne", List(user.email)) { groupOne =>
          withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>

            withCleanBillingProject(user) { billingProjectName =>
              withWorkspace(billingProjectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo)) { workspaceName =>

                // user is in members emails in two authdomain groups
                groupNameToMembersEmails(groupOne) should contain allElementsOf List(user.email)
                groupNameToMembersEmails(groupTwo) should contain allElementsOf List(user.email)

                // user can access workspace and see two authdomain groups
                val groups = Orchestration.workspaces.getAuthorizationDomainInWorkspace(billingProjectName, workspaceName)(user.makeAuthToken())
                groups should contain theSameElementsAs List(groupOne, groupTwo)

              }(user.makeAuthToken())
            }
          }
        }
      }

      "can be cloned and retain the auth domain" in {

        val user = UserPool.chooseAuthDomainUser
        implicit val authToken: AuthToken = authTokenDefault

        withGroup("GroupsApiSpec_groupOne", List(user.email)) { groupOne =>
          withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>

            withCleanBillingProject(defaultUser) { billingProjectName =>
              Orchestration.billing.addUserToBillingProject(billingProjectName, user.email, BillingProjectRole.User)

              val authDomains = Set(groupOne, groupTwo)
              val acl = List(AclEntry(user.email, WorkspaceAccessLevel.Reader))
              withWorkspace(billingProjectName, "GroupsApiSpec_workspace", authDomains, acl) { workspaceName =>

                val workspaceCloneName = workspaceName + "_clone"
                register cleanUp Try(Orchestration.workspaces.delete(billingProjectName, workspaceCloneName)(user.makeAuthToken())).recover({
                  case _: Exception =>
                })
                Orchestration.workspaces.clone(billingProjectName, workspaceName, billingProjectName, workspaceCloneName, authDomains)(user.makeAuthToken())

                // two authdomain groups should be in cloned workspace
                val groups = Orchestration.workspaces.getAuthorizationDomainInWorkspace(billingProjectName, workspaceCloneName)(user.makeAuthToken())
                groups should contain theSameElementsAs List(groupOne, groupTwo)

                Orchestration.workspaces.delete(billingProjectName, workspaceCloneName)(user.makeAuthToken())
              }
            }
          }
        }
      }


      "can be cloned and have a group added to the auth domain" in {

        val user = UserPool.chooseAuthDomainUser
        implicit val authToken: AuthToken = authTokenDefault

        withGroup("GroupsApiSpec_groupOne", List(user.email)) { groupOne =>
          withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>
            withGroup("GroupsApiSpec_groupThre", List(user.email)) { groupThreeName =>

              withCleanBillingProject(defaultUser) { billingProjectName =>
                Orchestration.billing.addUserToBillingProject(billingProjectName, user.email, BillingProjectRole.User)

                val authDomain = Set(groupOne, groupTwo)
                val acl = List(AclEntry(user.email, WorkspaceAccessLevel.Reader))
                withWorkspace(billingProjectName, "GroupsApiSpec_workspace", authDomain, acl) { workspaceName =>

                  val workspaceCloneName = workspaceName + "_clone"
                  register cleanUp Try(Orchestration.workspaces.delete(billingProjectName, workspaceCloneName)(user.makeAuthToken())).recover({
                    case _: Exception =>
                  })

                  val newAuthDomain = authDomain + groupThreeName
                  Orchestration.workspaces.clone(billingProjectName, workspaceName, billingProjectName, workspaceCloneName, newAuthDomain)(user.makeAuthToken())

                  // verify two authdomain groups is in cloned workspace
                  val groups = Orchestration.workspaces.getAuthorizationDomainInWorkspace(billingProjectName, workspaceCloneName)(user.makeAuthToken())
                  groups should contain theSameElementsAs List(groupOne, groupTwo, groupThreeName)

                  Orchestration.workspaces.delete(billingProjectName, workspaceCloneName)(user.makeAuthToken())

                }
              }
            }
          }
        }
      }

      // no groups
      "when the user is in none of the groups" - {
        "when shared with them" - {
          "can be seen but is not accessible" in {

            val user = UserPool.chooseStudent
            implicit val authToken: AuthToken = authTokenDefault

            withGroup("GroupsApiSpec_groupOne") { groupOne =>
              withGroup("GroupsApiSpec_groupTwo") { groupTwo =>

                withCleanBillingProject(defaultUser) { billingProjectName =>

                  val authDomain = Set(groupOne, groupTwo)
                  withWorkspace(billingProjectName, "GroupsApiSpec_workspace", authDomain, List(AclEntry(user.email, WorkspaceAccessLevel.Reader))) { workspaceName =>

                    // user can see workspace
                    val workspacenames: Seq[String] = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                    workspacenames should contain(workspaceName)

                    // user cannot access workspace
                    val response = Orchestration.workspaces.getWorkspaceDetail(billingProjectName, workspaceName)(user.makeAuthToken())
                    assertResult(StatusCodes.NotFound) {
                      response.status
                    }
                  }
                }
              }
            }
          }
        }

        "when the user is a billing project owner" - {
          "can be seen but is not accessible" in {

            val user = UserPool.chooseProjectOwner
            implicit val authToken: AuthToken = authTokenDefault

            withGroup("GroupsApiSpec_groupOne") { groupOne =>
              withGroup("GroupsApiSpec_groupOne") { groupTwo =>

                withCleanBillingProject(user, userEmails = List(defaultUser.email)) { billingProjectName =>
                  withWorkspace(billingProjectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo)) { workspaceName =>

                    // user can see workspace
                    val workspacenames: Seq[String] = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                    workspacenames should contain(workspaceName)

                    // user cannot access workspace
                    val response = Orchestration.workspaces.getWorkspaceDetail(billingProjectName, workspaceName)(user.makeAuthToken())
                    assertResult(StatusCodes.NotFound) {
                      response.status
                    }
                  }
                }
              }
            }
          }
        }

        "when not shared with them" - {
          "cannot be seen and is not accessible" in {
            val user = UserPool.chooseStudent
            implicit val authToken: AuthToken = authTokenDefault

            withGroup("GroupsApiSpec_groupOne") { groupOne =>
              withGroup("GroupsApiSpec_groupTwo") { groupTwo =>

                withCleanBillingProject(defaultUser) { projectName =>
                  withWorkspace(projectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo)) { workspaceName =>

                    // user cannot see workspace
                    val workspacenames = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                    workspacenames should not contain (workspaceName)

                    // user cannot access workspace
                    val response = Orchestration.workspaces.getWorkspaceDetail(projectName, workspaceName)(user.makeAuthToken())
                    assertResult(StatusCodes.NotFound) {
                      response.status
                    }
                  }
                }
              }
            }
          }
        }
      }

      // one group
      "when the user is in one of the groups" - {
        "when shared with them" - {
          "can be seen but is not accessible" in {

            val user = UserPool.chooseStudent
            implicit val authToken: AuthToken = authTokenDefault

            withGroup("GroupsApiSpec_groupOne") { groupOne =>
              withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>

                withCleanBillingProject(defaultUser) { billingProjectName =>

                  withWorkspace(billingProjectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo), List(AclEntry(user.email, WorkspaceAccessLevel.Reader))) { workspaceName =>

                    // user can see workspace
                    val workspacenames = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                    workspacenames should contain(workspaceName)

                    // user cannot access workspace
                    val response = Orchestration.workspaces.getWorkspaceDetail(billingProjectName, workspaceName)(user.makeAuthToken())
                    assertResult(StatusCodes.NotFound)(response.status)

                  }
                }
              }
            }
          }

          "when the user is a billing project owner" - {
            "can be seen but is not accessible" in {

              val user = UserPool.chooseProjectOwner
              implicit val authToken: AuthToken = authTokenDefault

              withGroup("GroupsApiSpec_groupOne") { groupOne =>
                withCleanBillingProject(defaultUser, List(user.email)) { projectName =>
                  withWorkspace(projectName, "GroupsApiSpec_workspace", Set(groupOne)) { workspaceName =>

                    // user can see workspace
                    val workspacenames = Orchestration.workspaces.getWorkspaceNames(user)
                    workspacenames should contain(workspaceName)

                    // user cannot access workspace
                    val response = Orchestration.workspaces.getWorkspaceDetail(projectName, workspaceName)
                    assertResult(StatusCodes.NotFound)(response.status)

                  }(user.makeAuthToken())
                }
              }(user.makeAuthToken())
            }
          }
        }

        "when not shared with them" - {
          "cannot be seen and is not accessible" in {

            val user = UserPool.chooseStudent
            implicit val authToken: AuthToken = authTokenDefault

            withGroup("GroupsApiSpec_groupOne") { groupOne =>
              withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>

                withCleanBillingProject(defaultUser) { projectName =>
                  withWorkspace(projectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo)) { workspaceName =>

                    // user cannot see workspace
                    val workspacenames = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                    workspacenames should not contain (workspaceName)

                    // user cannot access workspace
                    val response = Orchestration.workspaces.getWorkspaceDetail(projectName, workspaceName)(user.makeAuthToken())
                    assertResult(StatusCodes.NotFound)(response.status)

                  }
                }
              }
            }
          }
        }
      }


      // in all groups
      "when the user is in all of the groups" - {
        "when shared with them" - {
          "can be seen and is accessible" in {

            val user = UserPool.chooseStudent
            implicit val authToken: AuthToken = authTokenDefault

            withGroup("GroupsApiSpec_groupOne", List(user.email)) { groupOne =>
              withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>

                withCleanBillingProject(defaultUser) { projectName =>
                  withWorkspace(projectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo), List(AclEntry(user.email, WorkspaceAccessLevel.Reader))) { workspaceName =>

                    // user can see workspace
                    val allWorkspacenames: Seq[String] = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                    allWorkspacenames should contain(workspaceName)

                    // user can access workspace
                    val response = Orchestration.workspaces.getWorkspaceDetail(projectName, workspaceName)(user.makeAuthToken())
                    assertResult(StatusCodes.OK)(response.status)
                  }
                }
              }
            }
          }

          "and given writer access" - {
            "the user has correct permissions" in {

              val user = UserPool.chooseStudent
              implicit val authToken: AuthToken = authTokenDefault

              withGroup("GroupsApiSpec_groupOne", List(user.email)) { groupOne =>
                withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>

                  withCleanBillingProject(defaultUser) { projectName =>
                    withWorkspace(projectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo), List(AclEntry(user.email, WorkspaceAccessLevel.Writer))) { workspaceName =>

                      val level = getWorkspaceAccessLevel(projectName, workspaceName)(user.makeAuthToken())
                      level should (be("WRITER"))
                    }
                  }
                }
              }
            }
          }


          "when the user is a billing project owner" - {
            "can be seen and is accessible" in {

              val user = UserPool.chooseProjectOwner
              implicit val authToken: AuthToken = user.makeAuthToken()

              withGroup("GroupsApiSpec_groupOne", List(user.email)) { groupOne =>
                withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>

                  withCleanBillingProject(user) { projectName =>
                    withWorkspace(projectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo)) { workspaceName =>

                      // user can see workspace
                      val workspacenames: Seq[String] = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                      workspacenames should contain(workspaceName)
                      // user can access workspace
                      val response = Orchestration.workspaces.getWorkspaceDetail(projectName, workspaceName)(user.makeAuthToken())
                      assertResult(StatusCodes.OK)(response.status)
                    }
                  }
                }
              }
            }
          }
        }

        "when shared with one of the groups in the auth domain" - {
          "can be seen and is accessible by group member who is a member of both auth domain groups" in {

            val user = UserPool.chooseStudent
            implicit val authToken: AuthToken = authTokenDefault

            withGroup("GroupsApiSpec_groupOne", List(user.email)) { groupOne =>
              withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>

                withCleanBillingProject(defaultUser) { projectName =>
                  withWorkspace(projectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo), List(AclEntry(groupNameToEmail(groupOne), WorkspaceAccessLevel.Reader))) { workspaceName =>

                    // user can see workspace
                    val workspacenames: Seq[String] = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                    workspacenames should contain(workspaceName)
                    // user can access workspace
                    val response = Orchestration.workspaces.getWorkspaceDetail(projectName, workspaceName)(user.makeAuthToken())
                    assertResult(StatusCodes.OK)(response.status)
                  }
                }
              }
            }
          }

          "can be seen but is not accessible by group member who is a member of only one auth domain group" in {
            val user = UserPool.chooseStudent
            implicit val authToken: AuthToken = authTokenDefault
            withGroup("GroupsApiSpec_groupOne", List(user.email)) { groupOne =>
              withGroup("GroupsApiSpec_groupTwo") { groupTwo =>
                withCleanBillingProject(defaultUser) { projectName =>
                  withWorkspace(projectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo), List(AclEntry(groupNameToEmail(groupOne), WorkspaceAccessLevel.Reader))) { workspaceName =>

                    // user can see workspace
                    val workspacenames: Seq[String] = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                    workspacenames should contain(workspaceName)
                    // user cannot access workspace
                    val response = Orchestration.workspaces.getWorkspaceDetail(projectName, workspaceName)(user.makeAuthToken())
                    assertResult(StatusCodes.NotFound)(response.status)
                  }
                }
              }
            }
          }
        }

        "when not shared with them" - {
          "cannot be seen and is not accessible" in {
            val user = UserPool.chooseStudent
            implicit val authToken: AuthToken = authTokenDefault

            withGroup("GroupsApiSpec_groupOne", List(user.email)) { groupOne =>
              withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>

                withCleanBillingProject(defaultUser) { projectName =>
                  withWorkspace(projectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo)) { workspaceName =>

                    // user cannot see workspace
                    val workspacenames = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                    workspacenames should not contain (workspaceName)
                    // user cannot access workspace
                    val response = Orchestration.workspaces.getWorkspaceDetail(projectName, workspaceName)(user.makeAuthToken())
                    assertResult(StatusCodes.NotFound)(response.status)
                  }
                }
              }
            }
          }
        }

        //TCGA controlled access workspaces use-case
        "when the workspace is shared with the group" - {
          "can be seen and is accessible" in {

            val user = UserPool.chooseAuthDomainUser
            implicit val authToken: AuthToken = authTokenDefault

            withGroup("GroupsApiSpec_groupOne", List(user.email)) { groupOne =>
              withGroup("GroupsApiSpec_groupTwo", List(user.email)) { groupTwo =>

                withCleanBillingProject(defaultUser) { projectName =>
                  withWorkspace(projectName, "GroupsApiSpec_workspace", Set(groupOne, groupTwo), List(AclEntry(groupNameToEmail(groupOne), WorkspaceAccessLevel.Reader))) { workspaceName =>

                    // user can see workspace
                    val allWorkspacenames: Seq[String] = Orchestration.workspaces.getWorkspaceNames(user)(user.makeAuthToken())
                    allWorkspacenames should contain(workspaceName)
                    // user can access workspace and see two authdomain groups
                    val groups = Orchestration.workspaces.getAuthorizationDomainInWorkspace(projectName, workspaceName)(user.makeAuthToken())
                    groups should contain theSameElementsAs List(groupOne, groupTwo)
                  }
                }
              }
            }
          }
        }


      } // End of in all groups
    }

  }

  private def getWorkspaceAccessLevel(projectName: String, workspaceName: String)(implicit token: AuthToken): String = {
    import DefaultJsonProtocol._
    val response = Rawls.workspaces.getWorkspaceDetails(projectName, workspaceName)
    val json: JsValue = JsonParser(response)
    val field: JsValue = json.asJsObject.fields("accessLevel")
    field.convertTo[String]
  }

}
