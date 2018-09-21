package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.ManagedGroupService._
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.slf4j.LoggerFactory
import spray.http.HttpMethods
import spray.routing._

trait ManagedGroupApiService extends HttpService with PerRequestCreator with FireCloudRequestBuilding with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)

  val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService

  val managedGroupServiceRoutes =
    pathPrefix("api") {
      pathPrefix("groups") {
        pathEnd {
          get {
            requireUserInfo() { userInfo =>
              requestContext =>
                perRequest(requestContext, ManagedGroupService.props(managedGroupServiceConstructor, userInfo), ListGroups)
            }
          }
        } ~
        pathPrefix(Segment) { groupName =>
          pathEnd {
            get {
              requireUserInfo() { userInfo =>
                requestContext =>
                  perRequest(requestContext, ManagedGroupService.props(managedGroupServiceConstructor, userInfo), ListGroupMembers(WorkbenchGroupName(groupName)))
              }
            } ~
              post {
                requireUserInfo() { userInfo =>
                  requestContext =>
                    perRequest(requestContext, ManagedGroupService.props(managedGroupServiceConstructor, userInfo), CreateGroup(WorkbenchGroupName(groupName)))
                }
              } ~
              delete {
                requireUserInfo() { userInfo =>
                  requestContext =>
                    perRequest(requestContext, ManagedGroupService.props(managedGroupServiceConstructor, userInfo), DeleteGroup(WorkbenchGroupName(groupName)))
                }
              }
            } ~
            path("requestAccess") {
              post {
                requireUserInfo() { userInfo =>
                  requestContext =>
                    perRequest(requestContext, ManagedGroupService.props(managedGroupServiceConstructor, userInfo), RequestGroupAccess(WorkbenchGroupName(groupName)))
                }
              }
            } ~
            path(Segment / Segment) { (role, email) =>
              put {
                requireUserInfo() { userInfo =>
                  requestContext =>
                    perRequest(requestContext, ManagedGroupService.props(managedGroupServiceConstructor, userInfo), AddGroupMember(WorkbenchGroupName(groupName), ManagedGroupRoles.withName(role), WorkbenchEmail(email)))
                }
              } ~
              delete {
                requireUserInfo() { userInfo =>
                  requestContext =>
                    perRequest(requestContext, ManagedGroupService.props(managedGroupServiceConstructor, userInfo), RemoveGroupMember(WorkbenchGroupName(groupName), ManagedGroupRoles.withName(role), WorkbenchEmail(email)))
                }
              }
            }
          }
      }
    }
}