package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.{StandardUserInfoDirectives, UserInfoDirectives}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

trait ManagedGroupApiService extends Directives with RequestBuilding with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext

  lazy val log = LoggerFactory.getLogger(getClass)

  val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService

  val managedGroupServiceRoutes: Route = requireUserInfo() { userInfo =>
    pathPrefix("api") {
      pathPrefix("groups") {
        pathEnd {
          get {
            complete { managedGroupServiceConstructor(userInfo).ListGroups }
          }
        } ~
          pathPrefix(Segment) { groupName =>
            pathEnd {
              get {
                complete { managedGroupServiceConstructor(userInfo).ListGroupMembers(WorkbenchGroupName(groupName)) }
              } ~
              post {
                complete { managedGroupServiceConstructor(userInfo).CreateGroup(WorkbenchGroupName(groupName)) }
              } ~
              delete {
                complete { managedGroupServiceConstructor(userInfo).DeleteGroup(WorkbenchGroupName(groupName)) }
              }
            } ~
              path("requestAccess") {
                post {
                  complete { managedGroupServiceConstructor(userInfo).RequestGroupAccess(WorkbenchGroupName(groupName)) }
                }
              } ~
              path(Segment / Segment) { (role, email) =>
                put {
                  complete { managedGroupServiceConstructor(userInfo).AddGroupMember(WorkbenchGroupName(groupName), ManagedGroupRoles.withName(role), WorkbenchEmail(email)) }
                } ~
                  delete {
                    complete { managedGroupServiceConstructor(userInfo).RemoveGroupMember(WorkbenchGroupName(groupName), ManagedGroupRoles.withName(role), WorkbenchEmail(email)) }
                  }
              }
          }
      }
    }
  }
}
