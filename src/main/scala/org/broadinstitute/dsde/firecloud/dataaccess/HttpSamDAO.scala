package org.broadinstitute.dsde.firecloud.dataaccess

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import okhttp3.Dispatcher
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.ManagedGroupRoles.ManagedGroupRole
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.SamResource.UserPolicy
import org.broadinstitute.dsde.firecloud.model.{
  AccessToken,
  FireCloudManagedGroupMembership,
  ManagedGroupRoles,
  RegistrationInfo,
  SamUser,
  SamUserAttributesRequest,
  SamUserRegistrationRequest,
  SamUserResponse,
  UserIdInfo,
  UserInfo,
  WithAccessToken,
  WorkbenchUserInfo
}
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.rawls.RawlsException
import org.broadinstitute.dsde.rawls.model.{ErrorReport, RawlsUserEmail, WorkspaceJsonSupport}
import org.broadinstitute.dsde.workbench.client.sam.{ApiCallback, ApiClient, ApiException}
import org.broadinstitute.dsde.workbench.client.sam.api.{ResourcesApi, UsersApi}
import org.broadinstitute.dsde.workbench.client.sam.model.{BulkMembershipUpdateRequestV2, UserStatusInfo}
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.json.DefaultJsonProtocol._
import spray.json.{JsValue, JsonFormat, RootJsonFormat}

import java.util
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Created by mbemis on 8/21/17.
 */
class HttpSamDAO(implicit
  val system: ActorSystem,
  val materializer: Materializer,
  val executionContext: ExecutionContext
) extends SamDAO
    with RestJsonClient
    with SprayJsonSupport {

  val timeout: FiniteDuration = 1.minute
  private val dispatcher = new Dispatcher()
  dispatcher.setMaxRequests(1000)
  dispatcher.setMaxRequestsPerHost(100)
  dispatcher.executorService()
  private val httpClient = new ApiClient().getHttpClient.newBuilder().dispatcher(dispatcher).build()

  override def listWorkspaceResources(implicit userInfo: WithAccessToken): Future[Seq[UserPolicy]] =
    authedRequestToObject[Seq[UserPolicy]](Get(samListResources("workspace")),
                                           label = Some("HttpSamDAO.listWorkspaceResources")
    )

  override def registerUser(
    termsOfService: Option[String]
  )(implicit userInfo: WithAccessToken): Future[RegistrationInfo] =
    authedRequestToObject[RegistrationInfo](Post(samUserRegistrationUrl, termsOfService),
                                            label = Some("HttpSamDAO.registerUser")
    )

  override def registerUserSelf(acceptsTermsOfService: Boolean)(implicit
    userInfo: WithAccessToken
  ): Future[SamUserResponse] =
    authedRequestToObject[SamUserResponse](
      Post(samUserRegisterSelfUrl,
           SamUserRegistrationRequest(acceptsTermsOfService, SamUserAttributesRequest(marketingConsent = Some(false)))
      ),
      label = Some("HttpSamDAO.registerUserSelf")
    )

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] =
    authedRequestToObject[RegistrationInfo](Get(samUserRegistrationUrl),
                                            label = Some("HttpSamDAO.getRegistrationStatus")
    )

  override def getUserIds(email: RawlsUserEmail)(implicit userInfo: WithAccessToken): Future[UserIdInfo] =
    authedRequestToObject[UserIdInfo](Get(samGetUserIdsUrl.format(URLEncoder.encode(email.value, UTF_8.name))))

  // Sam's API only allows for 1000 user to be fetched at one time
  override def getUsersForIds(
    samUserIds: Seq[WorkbenchUserId]
  )(implicit userInfo: WithAccessToken): Future[Seq[WorkbenchUserInfo]] = Future
    .sequence {
      samUserIds.sliding(1000, 1000).toSeq.map { batch =>
        adminAuthedRequestToObject[Seq[SamUser]](Post(samAdminGetUsersForIdsUrl, batch))
          .map(_.map(user => WorkbenchUserInfo(user.id.value, user.email.value)))
      }
    }
    .map(_.flatten)

  override def isGroupMember(groupName: WorkbenchGroupName, userInfo: UserInfo): Future[Boolean] = {
    implicit val accessToken = userInfo
    authedRequestToObject[List[String]](Get(samResourceRoles(managedGroupResourceTypeName, groupName.value)),
                                        label = Some("HttpSamDAO.isGroupMember")
    ).map { allRoles =>
      allRoles.map(ManagedGroupRoles.withName).toSet.intersect(ManagedGroupRoles.membershipRoles).nonEmpty
    }
  }

  override def createGroup(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit] =
    userAuthedRequestToUnit(Post(samManagedGroup(groupName)))

  override def deleteGroup(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit] =
    userAuthedRequestToUnit(Delete(samManagedGroup(groupName)))

  override def listGroups(implicit userInfo: WithAccessToken): Future[List[FireCloudManagedGroupMembership]] =
    authedRequestToObject[List[FireCloudManagedGroupMembership]](Get(samManagedGroupsBase))

  override def getGroupEmail(groupName: WorkbenchGroupName)(implicit
    userInfo: WithAccessToken
  ): Future[WorkbenchEmail] =
    authedRequestToObject[WorkbenchEmail](Get(samManagedGroup(groupName)))

  override def listGroupPolicyEmails(groupName: WorkbenchGroupName, policyName: ManagedGroupRole)(implicit
    userInfo: WithAccessToken
  ): Future[List[WorkbenchEmail]] =
    authedRequestToObject[List[WorkbenchEmail]](Get(samManagedGroupPolicy(groupName, policyName)))

  override def addGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail)(implicit
    userInfo: WithAccessToken
  ): Future[Unit] =
    userAuthedRequestToUnit(Put(samManagedGroupAlterMember(groupName, role, email)))

  override def removeGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail)(implicit
    userInfo: WithAccessToken
  ): Future[Unit] =
    userAuthedRequestToUnit(Delete(samManagedGroupAlterMember(groupName, role, email)))

  override def overwriteGroupMembers(groupName: WorkbenchGroupName,
                                     role: ManagedGroupRole,
                                     memberList: List[WorkbenchEmail]
  )(implicit userInfo: WithAccessToken): Future[Unit] =
    userAuthedRequestToUnit(Put(samManagedGroupPolicy(groupName, role), memberList))

  override def addPolicyMember(resourceTypeName: String, resourceId: String, policyName: String, email: WorkbenchEmail)(
    implicit userInfo: WithAccessToken
  ): Future[Unit] =
    userAuthedRequestToUnit(Put(samResourcePolicyAlterMember(resourceTypeName, resourceId, policyName, email)))

  override def setPolicyPublic(resourceTypeName: String, resourceId: String, policyName: String, public: Boolean)(
    implicit userInfo: WithAccessToken
  ): Future[Unit] = {
    implicit val booleanFormat = new RootJsonFormat[Boolean] {
      override def read(json: JsValue): Boolean = implicitly[JsonFormat[Boolean]].read(json)
      override def write(obj: Boolean): JsValue = implicitly[JsonFormat[Boolean]].write(obj)
    }

    userAuthedRequestToUnit(Put(samResourcePolicy(resourceTypeName, resourceId, policyName) + "/public", public))
  }

  override def requestGroupAccess(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit] =
    userAuthedRequestToUnit(Post(samManagedGroupRequestAccess(groupName)))

  private def userAuthedRequestToUnit(request: HttpRequest)(implicit userInfo: WithAccessToken): Future[Unit] =
    userAuthedRequest(request).flatMap { resp =>
      if (resp.status.isSuccess) Future.successful {
        resp.discardEntityBytes()
      }
      else {
        FCErrorReport(resp).flatMap { errorReport =>
          Future.failed(new FireCloudExceptionWithErrorReport(errorReport))
        }
      }
    }

  override def getPetServiceAccountTokenForUser(user: WithAccessToken, scopes: Seq[String]): Future[AccessToken] = {
    implicit val accessToken = user

    authedRequestToObject[String](Post(samArbitraryPetTokenUrl, scopes),
                                  label = Some("HttpSamDAO.getPetServiceAccountTokenForUser")
    ).map { quotedToken =>
      // Sam returns a quoted string. We need the token without the quotes.
      val token =
        if (quotedToken.startsWith("\"") && quotedToken.endsWith("\""))
          quotedToken.substring(1, quotedToken.length - 1)
        else
          quotedToken
      AccessToken.apply(token)
    }
  }

  def getPetServiceAccountKeyForUser(user: WithAccessToken, project: GoogleProject): Future[String] = {
    implicit val accessToken = user

    authedRequestToObject[String](Get(samPetKeyForProject.format(project.value)),
                                  label = Some("HttpSamDAO.getPetServiceAccountKeyForUser")
    )
  }

  override def status: Future[SubsystemStatus] =
    for {
      response <- unAuthedRequest(Get(samStatusUrl))
      ok = response.status.isSuccess
      message <- if (ok) Future.successful(None) else Unmarshal(response.entity).to[String].map(Option(_))
    } yield SubsystemStatus(ok, message.map(List(_)))

  override def bulkUpdateGroups(request: List[BulkMembershipUpdateRequestV2], user: WithAccessToken): Future[Unit] = {
    val sam = new ResourcesApi(newApiClient(user))
    val callback = new SamApiCallback[Void]("bulkMembershipUpdateV2")

    sam.bulkMembershipUpdateV2Async(request.asJava, callback)
    callback.future.map(_ => ())
  }

  override def getUserStatus(user: WithAccessToken): Future[UserStatusInfo] = {
    val apiClient = newApiClient(user)
    val sam = new UsersApi(apiClient)
    val callback = new SamApiCallback[UserStatusInfo]("getUserEnabled")
    sam.getUserStatusInfoAsync(callback)
    callback.future
  }

  private def newApiClient(user: WithAccessToken) = {
    val apiClient = new ApiClient(httpClient)
    apiClient.setAccessToken(user.accessToken.token)
    apiClient.setBasePath(FireCloudConfig.Sam.baseUrl)
    apiClient
  }

  private class SamApiCallback[T](functionName: String) extends ApiCallback[T] {
    private val promise = Promise[T]()

    override def onFailure(e: ApiException,
                           statusCode: Int,
                           responseHeaders: util.Map[String, util.List[String]]
    ): Unit =
      try {
        val response = Option(e.getResponseBody).getOrElse(e.getMessage)

        // attempt to propagate an ErrorReport from Sam. If we can't understand Sam's response as an ErrorReport,
        // create our own error message.
        import WorkspaceJsonSupport.ErrorReportFormat
        import spray.json._
        val errorReport = Try(response.parseJson.convertTo[ErrorReport]).recover { case _: Throwable =>
          val sc = Try(StatusCode.int2StatusCode(statusCode)).getOrElse(StatusCodes.InternalServerError)
          ErrorReport(sc, s"Sam call to $functionName failed with error '$response'", e)
        }.get

        val exceptionWithErrorReport = new FireCloudExceptionWithErrorReport(errorReport)
        logger.info(s"Sam call to $functionName failed", exceptionWithErrorReport)
        promise.failure(exceptionWithErrorReport)
      } catch {
        case wtf: Throwable =>
          logger.info("unexpected exception parsing error response from sam, failing with raw error", wtf)
          // must be 100% certain that promise.failure is called otherwise the promise will never be fulfilled
          promise.failure(e)
      }

    override def onSuccess(result: T, statusCode: Int, responseHeaders: util.Map[String, util.List[String]]): Unit =
      promise.success(result)

    override def onUploadProgress(bytesWritten: Long, contentLength: Long, done: Boolean): Unit = ()

    override def onDownloadProgress(bytesRead: Long, contentLength: Long, done: Boolean): Unit = ()

    def future: Future[T] = {
      val timeoutFuture: Future[T] = akka.pattern.after(timeout, system.scheduler)(
        Future.failed(new RawlsException(s"Sam call to $functionName timed out"))
      )
      Future.firstCompletedOf(Seq(promise.future, timeoutFuture))
    }
  }
}
