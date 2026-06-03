package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{ExternalCredsDAO, GoogleServicesDAO, SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.{
  ConsentGroup,
  DbGapPermission,
  ExternalCredsMessage,
  FireCloudKeyValue,
  FireCloudManagedGroupMembership,
  ManagedGroupRoles,
  PhsId,
  ProfileWrapper,
  SamUser,
  UserInfo,
  WithAccessToken,
  WorkbenchUserInfo
}
import org.broadinstitute.dsde.workbench.model.{
  AzureB2CId,
  GoogleSubjectId,
  WorkbenchEmail,
  WorkbenchGroupName,
  WorkbenchUserId
}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.broadinstitute.dsde.workbench.client.sam.model.{BulkMembershipUpdateRequestV2, PolicyMembershipUpdate}
import org.broadinstitute.dsde.workbench.util2.messaging.{AckHandler, ReceivedMessage}
import org.joda.time.DateTime
import org.mockito.{ArgumentMatchers, Mockito}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, times, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util
import java.util.UUID

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Random, Success}

class NihServiceUnitSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  case class NihTestAccount(userId: String, linkedExternalId: String, linkExpireTime: DateTime)

  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val errorReportSource: ErrorReportSource = ErrorReportSource("NihServiceUnitSpec")
  val samDao = mock[SamDAO]
  val thurloeDao = mock[ThurloeDAO]
  val googleDao = mock[GoogleServicesDAO]
  val ecmDao = mock[ExternalCredsDAO]

  // build the service instance we'll use for tests
  val nihService = new NihService(samDao, thurloeDao, googleDao, ecmDao)

  val userNoLinkedAccount = genSamUser();
  val userNoAllowlists = genSamUser()
  val userTcgaAndTarget = genSamUser();
  val userTcgaOnly = genSamUser();
  val userTargetOnly = genSamUser();
  val userDbGap = genSamUser();
  val userDbGapBoth = genSamUser();
  val deniedUser = genSamUser().copy(email = WorkbenchEmail("someone@gmAil.com"))
  val dbGapGroupEmail = WorkbenchEmail(UUID.randomUUID().toString + "@email.com")

  // DateTimes must be modified in seconds instead of days to match implementation
  val secondsIn30Days = 30.days.toSeconds.toInt

  var userNoAllowlistsLinkedAccount =
    NihTestAccount(userNoAllowlists.id.value, "nihUsername1", new DateTime().plusSeconds(secondsIn30Days))
  var userTcgaAndTargetLinkedAccount =
    NihTestAccount(userTcgaAndTarget.id.value, "nihUsername2", new DateTime().plusSeconds(secondsIn30Days))
  var userTcgaOnlyLinkedAccount =
    NihTestAccount(userTcgaOnly.id.value, "nihUsername3", new DateTime().plusSeconds(secondsIn30Days))
  var userTargetOnlyLinkedAccount =
    NihTestAccount(userTargetOnly.id.value, "nihUsername4", new DateTime().plusSeconds(secondsIn30Days))
  var userDbGapLinkedAccount =
    NihTestAccount(userDbGap.id.value, "nihUsername5", new DateTime().plusSeconds(secondsIn30Days))

  val samUsers =
    Seq(userNoLinkedAccount,
        userNoAllowlists,
        userTcgaAndTarget,
        userTcgaOnly,
        userTargetOnly,
        userDbGap,
        userDbGapBoth,
        deniedUser
    )
  val idToSamUser = samUsers.groupBy(_.id).view.mapValues(_.head).toMap

  val linkedAccountsBySamUserId = Map(
    userNoAllowlists.id -> userNoAllowlistsLinkedAccount,
    userTcgaAndTarget.id -> userTcgaAndTargetLinkedAccount,
    userTcgaOnly.id -> userTcgaOnlyLinkedAccount,
    userTargetOnly.id -> userTargetOnlyLinkedAccount,
    userDbGap.id -> userDbGapLinkedAccount,
    deniedUser.id -> userTcgaAndTargetLinkedAccount
  )

  val samUserToGroups =
    Map(
      userNoLinkedAccount.id -> Set("other-group"),
      userNoAllowlists.id -> Set("other-group"),
      userTcgaAndTarget.id -> Set("TCGA-dbGaP-Authorized", "TARGET-dbGaP-Authorized", "other-group"),
      userTcgaOnly.id -> Set("TCGA-dbGaP-Authorized", "other-group"),
      userTargetOnly.id -> Set("TARGET-dbGaP-Authorized", "other-group"),
      userDbGap.id -> Set("dbgap_phs002409_c1"),
      userDbGapBoth.id -> Set("dbgap_phs002409_c1", "dbgap_phs002410_c1")
    )

  val samGroupMemberships =
    Map(
      "TCGA-dbGaP-Authorized" -> Set(userTcgaAndTarget.id, userTcgaOnly.id),
      "TARGET-dbGaP-Authorized" -> Set(userTcgaAndTarget.id, userTargetOnly.id),
      "dbgap_phs002409_c1" -> Set(userDbGap.id),
      "this-doesnt-matter" -> Set.empty
    )

  val accessTokenToUser =
    Map(
      UUID.randomUUID().toString -> userNoLinkedAccount.id,
      UUID.randomUUID().toString -> userNoAllowlists.id,
      UUID.randomUUID().toString -> userTcgaAndTarget.id,
      UUID.randomUUID().toString -> userTcgaOnly.id,
      UUID.randomUUID().toString -> userTargetOnly.id,
      UUID.randomUUID().toString -> userDbGap.id,
      UUID.randomUUID().toString -> userDbGapBoth.id
    )

  val userToAccessToken = accessTokenToUser.map(_.swap)
  val adminAccessToken = UUID.randomUUID().toString

  override def beforeEach(): Unit = {
    Mockito.reset(thurloeDao, ecmDao, googleDao, samDao)
    mockSamUsers()
    mockGoogleServicesDAO()
  }

  "getNihStatus" should "return status from Thurloe" in {
    mockThurloeUsers()
    val user = userTcgaAndTarget
    val userInfo = UserInfo(userToAccessToken(user.id), userTcgaAndTarget.id.value)
    val nihStatus = Await
      .result(nihService.getNihStatus(userInfo), Duration.Inf)
      .asInstanceOf[PerRequest.RequestComplete[NihStatus]]
      .response
    nihStatus.linkedNihUsername shouldBe Some(linkedAccountsBySamUserId(WorkbenchUserId(userInfo.id)).linkedExternalId)
    verify(thurloeDao).getAllKVPs(user.id.value, userInfo)
  }

  it should "return None if no linked account is found" in {
    when(thurloeDao.getAllKVPs(any[String], any[WithAccessToken])).thenReturn(Future.successful(None))
    val user = userNoLinkedAccount
    val userInfo = UserInfo(userToAccessToken(user.id), userNoLinkedAccount.id.value)
    val nihStatus = Await
      .result(nihService.getNihStatus(userInfo), Duration.Inf)
      .asInstanceOf[PerRequest.RequestComplete[StatusCode]]
      .response
    nihStatus should be(StatusCodes.NotFound)
  }

  it should "return None if a user if found in Thurloe, but no linkedNihUsername exists" in {
    when(thurloeDao.getAllKVPs(any[String], any[WithAccessToken]))
      .thenReturn(
        Future.successful(
          Some(
            ProfileWrapper(userNoLinkedAccount.id.value,
                           List(FireCloudKeyValue(Some("email"), Some(userNoLinkedAccount.email.value)))
            )
          )
        )
      )
    val user = userNoLinkedAccount
    val userInfo = UserInfo(userToAccessToken(user.id), userNoLinkedAccount.id.value)
    val nihStatus = Await
      .result(nihService.getNihStatus(userInfo), Duration.Inf)
      .asInstanceOf[PerRequest.RequestComplete[StatusCode]]
      .response
    nihStatus should be(StatusCodes.NotFound)
  }

  private def verifyTargetGroupSynced(): Unit = {
    val emailsToSync = Set(userTcgaAndTarget.email, userTargetOnly.email)
    val nihStatus = Await
      .result(nihService.syncAllowlistAllUsers("TARGET"), Duration.Inf)
      .asInstanceOf[PerRequest.RequestComplete[StatusCode]]
      .response

    nihStatus should be(StatusCodes.NoContent)
    verify(googleDao, never()).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "tcga-whitelist.txt")
    verify(googleDao, times(1))
      .getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "target-whitelist.txt")
    verify(samDao, times(1)).overwriteGroupMembers(
      ArgumentMatchers.eq(WorkbenchGroupName("TARGET-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.argThat((list: List[WorkbenchEmail]) => list.toSet.equals(emailsToSync))
    )(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(samDao, never()).overwriteGroupMembers(
      ArgumentMatchers.eq(WorkbenchGroupName("other-group")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.argThat((list: List[WorkbenchEmail]) => list.toSet.equals(emailsToSync))
    )(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
  }

  "getNihResources" should "return authorized dbGap permissions based on Sam group membership" in {
    val user = userDbGap
    val userInfo = UserInfo(userToAccessToken(user.id), user.id.value)

    val resources = Await.result(nihService.getNihResources(userInfo), Duration.Inf)

    resources.datasetPermissions should contain allOf (
      NihDatasetPermission("dbgap_phs002409_c1", authorized = true),
      NihDatasetPermission("dbgap_phs002410_c1", authorized = false)
    )
  }

  it should "return all authorized when user is in all dbGap groups" in {
    val user = userDbGapBoth
    val userInfo = UserInfo(userToAccessToken(user.id), user.id.value)

    val resources = Await.result(nihService.getNihResources(userInfo), Duration.Inf)

    resources.datasetPermissions should contain allOf (
      NihDatasetPermission("dbgap_phs002409_c1", authorized = true),
      NihDatasetPermission("dbgap_phs002410_c1", authorized = true)
    )
  }

  it should "return all unauthorized when user has no dbGap group memberships" in {
    val user = userNoLinkedAccount
    val userInfo = UserInfo(userToAccessToken(user.id), user.id.value)

    val resources = Await.result(nihService.getNihResources(userInfo), Duration.Inf)

    resources.datasetPermissions should contain allOf (
      NihDatasetPermission("dbgap_phs002409_c1", authorized = false),
      NihDatasetPermission("dbgap_phs002410_c1", authorized = false)
    )
  }

  "syncAllowlistAllUsers" should "sync all users for a single allowlist from Thurloe" in {
    mockThurloeUsers()

    verifyTargetGroupSynced()
  }

  it should "sync all users by including groups found with consentGroup + phsId" in {
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("linkedNihUsername")))
      .thenReturn(Future.successful(Map(userDbGap.id.value -> userDbGapLinkedAccount.linkedExternalId)))
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("linkExpireTime")))
      .thenReturn(
        Future.successful(Map(userDbGap.id.value -> (userDbGapLinkedAccount.linkExpireTime.getMillis / 1000L).toString))
      )

    val emailsToSync = Set(userDbGap.email, dbGapGroupEmail)
    val nihStatus = Await
      .result(nihService.syncAllowlistAllUsers("RAS"), Duration.Inf)
      .asInstanceOf[PerRequest.RequestComplete[StatusCode]]
      .response

    nihStatus should be(StatusCodes.NoContent)
    verify(samDao, times(1)).overwriteGroupMembers(
      ArgumentMatchers.eq(WorkbenchGroupName("dbgap_phs002409_c1")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.argThat((list: List[WorkbenchEmail]) => list.toSet.equals(emailsToSync))
    )(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
  }

  it should "sync all users by tolerating groups found with consentGroup + phsId that don't exist" in {
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("linkedNihUsername")))
      .thenReturn(Future.successful(Map(userDbGap.id.value -> userDbGapLinkedAccount.linkedExternalId)))
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("linkExpireTime")))
      .thenReturn(
        Future.successful(Map(userDbGap.id.value -> (userDbGapLinkedAccount.linkExpireTime.getMillis / 1000L).toString))
      )
    when(samDao.getGroupEmail(ArgumentMatchers.eq(WorkbenchGroupName("dbgap_phs002409_c1")))(any())).thenReturn(
      Future.failed(
        new FireCloudExceptionWithErrorReport(
          ErrorReport(StatusCodes.NotFound, "Group not found")
        )
      )
    )

    val emailsToSync = Set(userDbGap.email)
    val nihStatus = Await
      .result(nihService.syncAllowlistAllUsers("RAS"), Duration.Inf)
      .asInstanceOf[PerRequest.RequestComplete[StatusCode]]
      .response

    nihStatus should be(StatusCodes.NoContent)
    verify(samDao, times(1)).overwriteGroupMembers(
      ArgumentMatchers.eq(WorkbenchGroupName("dbgap_phs002409_c1")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.argThat((list: List[WorkbenchEmail]) => list.toSet.equals(emailsToSync))
    )(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
  }

  it should "respond with NOT FOUND if no allowlist is found" in {
    val nihStatus = Await
      .result(nihService.syncAllowlistAllUsers("NOT_FOUND"), Duration.Inf)
      .asInstanceOf[PerRequest.RequestComplete[StatusCode]]
      .response

    nihStatus should be(StatusCodes.NotFound)

  }

  it should "recover from a Sam API Exception with a FirecloudException" in {
    val errorMessage = "Oops :("
    Mockito.reset(samDao)
    mockThurloeUsers()
    when(samDao.getUsersForIds(any[Seq[WorkbenchUserId]])(ArgumentMatchers.eq(UserInfo(adminAccessToken, ""))))
      .thenAnswer { args =>
        val userIds = args.getArgument(0).asInstanceOf[Seq[WorkbenchUserId]]
        Future.successful(
          samUsers
            .filter(user => userIds.contains(WorkbenchUserId(user.id.value)))
            .map(user => WorkbenchUserInfo(user.id.value, user.email.value))
        )
      }
    when(samDao.overwriteGroupMembers(any(), any(), any())(any()))
      .thenReturn(Future.failed(new RuntimeException(errorMessage)))
    when(samDao.listGroups(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))).thenReturn(
      Future.successful(
        samGroupMemberships.keys
          .map(groupName => FireCloudManagedGroupMembership(groupName, groupName + "@firecloud.org", "member"))
          .toList
      )
    )
    when(samDao.createGroup(any[WorkbenchGroupName])(ArgumentMatchers.eq(UserInfo(adminAccessToken, ""))))
      .thenReturn(Future.successful(()))

    val ex = intercept[FireCloudException] {
      Await.result(nihService.syncAllowlistAllUsers("TARGET"), Duration.Inf)
    }
    ex.getMessage should include(errorMessage)
  }

  "syncAllNihWhitelistsAllUsers" should "sync all allowlists for all users" in {
    mockThurloeUsers()

    val targetEmailsToSync =
      Set(WorkbenchEmail(userTcgaAndTarget.email.value), WorkbenchEmail(userTargetOnly.email.value))
    val tcgaUsersToSync = Set(WorkbenchEmail(userTcgaAndTarget.email.value), WorkbenchEmail(userTcgaOnly.email.value))
    val nihStatus = Await
      .result(nihService.syncAllNihAllowlistsAllUsers(), Duration.Inf)
      .asInstanceOf[PerRequest.RequestComplete[StatusCode]]
      .response

    nihStatus should be(StatusCodes.NoContent)
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "tcga-whitelist.txt")
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket,
                                                             "target-whitelist.txt"
    )
    verify(samDao, times(1)).overwriteGroupMembers(
      ArgumentMatchers.eq(WorkbenchGroupName("TARGET-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.argThat((list: List[WorkbenchEmail]) => list.toSet.equals(targetEmailsToSync))
    )(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(samDao, times(1)).overwriteGroupMembers(
      ArgumentMatchers.eq(WorkbenchGroupName("TCGA-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.argThat((list: List[WorkbenchEmail]) => list.toSet.equals(tcgaUsersToSync))
    )(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
  }

  "unlinkNihAccountAndSyncSelf" should "remove links from Thurloe and sync allowlists" in {
    mockThurloeUsers()

    val user = userTcgaOnly
    val userInfo = UserInfo(user.email.value,
                            OAuth2BearerToken(user.id.value),
                            Instant.now().plusSeconds(60).getEpochSecond,
                            user.id.value
    )
    Await.result(nihService.unlinkNihAccountAndSyncSelf(userInfo), Duration.Inf)

    verify(samDao, times(1)).removeGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("TCGA-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email.value))
    )(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(thurloeDao, times(1)).deleteKeyValue(user.id.value, "linkedNihUsername", userInfo)
    verify(thurloeDao, times(1)).deleteKeyValue(user.id.value, "linkExpireTime", userInfo)

  }

  /**
   * Verify that given config with 2 dbgap group and a user with a visa with 1 permission,
   * the user is added to the group and removed from the other group
   */
  "processExternalCredsMessage" should "add and remove user" in {
    val ackHandler = mock[AckHandler]
    val provider = "ras"
    val userId = UUID.randomUUID().toString

    // these match config
    val addPermission = DbGapPermission(PhsId("phs002409"), ConsentGroup("c1"))
    val removePermissions = DbGapPermission(PhsId("phs002410"), ConsentGroup("c1"))

    val visa = new java.util.HashMap[String, Object]()
    val dbGapPermissions = new util.ArrayList[util.HashMap[String, Object]]()
    visa.put("ras_dbgap_permissions", dbGapPermissions)
    val permission = new java.util.HashMap[String, Object]()
    permission.put("phs_id", addPermission.phsId.value)
    permission.put("consent_group", addPermission.consentGroup.value)
    permission.put("expiration",
                   java.lang.Integer.valueOf(Instant.now().plusSeconds(60).getEpochSecond.intValue) // future expiration
    )
    dbGapPermissions.add(permission)

    val orchAdmin = UserInfo(adminAccessToken, "")
    when(
      ecmDao.getVisas(provider, userId, FireCloudConfig.Nih.rasIssuer, FireCloudConfig.Nih.rasVisaType, orchAdmin)
    ).thenReturn(Future.successful(Seq(visa)))

    when(samDao.listGroups(orchAdmin)).thenReturn(Future.successful(FireCloudConfig.Nih.dbGapPermissionToGroup.map {
      case (_, groupName) => FireCloudManagedGroupMembership(groupName, groupName + "@firecloud.org", "admin")
    }.toList))

    when(
      samDao.bulkUpdateGroups(
        List(
          new BulkMembershipUpdateRequestV2()
            .resourceTypeName(FireCloudConfig.Sam.groupResourceType)
            .resourceId(
              FireCloudConfig.Nih.dbGapPermissionToGroup(addPermission)
            )
            .addPolicyUpdatesItem(
              new PolicyMembershipUpdate().policyName(FireCloudConfig.Sam.groupMemberPolicy).addAddUserIdsItem(userId)
            ),
          new BulkMembershipUpdateRequestV2()
            .resourceTypeName(FireCloudConfig.Sam.groupResourceType)
            .resourceId(
              FireCloudConfig.Nih.dbGapPermissionToGroup(removePermissions)
            )
            .addPolicyUpdatesItem(
              new PolicyMembershipUpdate()
                .policyName(FireCloudConfig.Sam.groupMemberPolicy)
                .addRemoveUserIdsItem(userId)
            )
        ),
        orchAdmin
      )
    ).thenReturn(Future.successful(()))

    nihService
      .processExternalCredsMessage(
        ReceivedMessage[ExternalCredsMessage](ExternalCredsMessage(provider, userId), None, Instant.now(), ackHandler)
      )
      .unsafeRunSync()

    verify(ackHandler).ack()
  }

  /**
   * same as add and remove user but the permission is expired so the user should be removed from both groups
   */
  it should "ignore expired permission" in {
    val ackHandler = mock[AckHandler]
    val provider = "ras"
    val userId = UUID.randomUUID().toString

    // these match config
    val expiredPermission = DbGapPermission(PhsId("phs002409"), ConsentGroup("c1"))
    val removePermissions = DbGapPermission(PhsId("phs002410"), ConsentGroup("c1"))

    val visa = new java.util.HashMap[String, Object]()
    val dbGapPermissions = new util.ArrayList[util.HashMap[String, Object]]()
    visa.put("ras_dbgap_permissions", dbGapPermissions)
    val permission = new java.util.HashMap[String, Object]()
    permission.put("phs_id", expiredPermission.phsId.value)
    permission.put("consent_group", expiredPermission.consentGroup.value)
    permission.put("expiration",
                   java.lang.Integer.valueOf(Instant.now().minusSeconds(60).getEpochSecond.intValue) // past expiration
    )
    dbGapPermissions.add(permission)

    val orchAdmin = UserInfo(adminAccessToken, "")
    when(
      ecmDao.getVisas(provider, userId, FireCloudConfig.Nih.rasIssuer, FireCloudConfig.Nih.rasVisaType, orchAdmin)
    ).thenReturn(Future.successful(Seq(visa)))

    when(samDao.listGroups(orchAdmin)).thenReturn(Future.successful(FireCloudConfig.Nih.dbGapPermissionToGroup.map {
      case (_, groupName) => FireCloudManagedGroupMembership(groupName, groupName + "@firecloud.org", "admin")
    }.toList))

    when(
      samDao.bulkUpdateGroups(
        List(
          new BulkMembershipUpdateRequestV2()
            .resourceTypeName(FireCloudConfig.Sam.groupResourceType)
            .resourceId(
              FireCloudConfig.Nih.dbGapPermissionToGroup(expiredPermission)
            )
            .addPolicyUpdatesItem(
              new PolicyMembershipUpdate()
                .policyName(FireCloudConfig.Sam.groupMemberPolicy)
                .addRemoveUserIdsItem(userId)
            ),
          new BulkMembershipUpdateRequestV2()
            .resourceTypeName(FireCloudConfig.Sam.groupResourceType)
            .resourceId(
              FireCloudConfig.Nih.dbGapPermissionToGroup(removePermissions)
            )
            .addPolicyUpdatesItem(
              new PolicyMembershipUpdate()
                .policyName(FireCloudConfig.Sam.groupMemberPolicy)
                .addRemoveUserIdsItem(userId)
            )
        ),
        orchAdmin
      )
    ).thenReturn(Future.successful(()))

    nihService
      .processExternalCredsMessage(
        ReceivedMessage[ExternalCredsMessage](ExternalCredsMessage(provider, userId), None, Instant.now(), ackHandler)
      )
      .unsafeRunSync()

    verify(ackHandler).ack()
  }

  it should "remove all permissions when no visas" in {
    val ackHandler = mock[AckHandler]
    val provider = "ras"
    val userId = UUID.randomUUID().toString

    val orchAdmin = UserInfo(adminAccessToken, "")
    when(
      ecmDao.getVisas(provider, userId, FireCloudConfig.Nih.rasIssuer, FireCloudConfig.Nih.rasVisaType, orchAdmin)
    ).thenReturn(Future.successful(Seq.empty))

    when(samDao.listGroups(orchAdmin)).thenReturn(Future.successful(FireCloudConfig.Nih.dbGapPermissionToGroup.map {
      case (_, groupName) => FireCloudManagedGroupMembership(groupName, groupName + "@firecloud.org", "admin")
    }.toList))

    when(
      samDao.bulkUpdateGroups(
        FireCloudConfig.Nih.dbGapPermissionToGroup.map { case (_, groupName) =>
          new BulkMembershipUpdateRequestV2()
            .resourceTypeName(FireCloudConfig.Sam.groupResourceType)
            .resourceId(groupName)
            .addPolicyUpdatesItem(
              new PolicyMembershipUpdate()
                .policyName(FireCloudConfig.Sam.groupMemberPolicy)
                .addRemoveUserIdsItem(userId)
            )
        }.toList,
        orchAdmin
      )
    ).thenReturn(Future.successful(()))

    nihService
      .processExternalCredsMessage(
        ReceivedMessage[ExternalCredsMessage](ExternalCredsMessage(provider, userId), None, Instant.now(), ackHandler)
      )
      .unsafeRunSync()

    verify(ackHandler).ack()
  }

  it should "nack when there is an exception" in {
    val ackHandler = mock[AckHandler]
    val provider = "ras"
    val userId = UUID.randomUUID().toString

    val orchAdmin = UserInfo(adminAccessToken, "")
    when(
      ecmDao.getVisas(provider, userId, FireCloudConfig.Nih.rasIssuer, FireCloudConfig.Nih.rasVisaType, orchAdmin)
    ).thenReturn(Future.successful(Seq.empty))

    when(samDao.listGroups(orchAdmin)).thenReturn(Future.successful(FireCloudConfig.Nih.dbGapPermissionToGroup.map {
      case (_, groupName) => FireCloudManagedGroupMembership(groupName, groupName + "@firecloud.org", "admin")
    }.toList))

    when(
      samDao.bulkUpdateGroups(any(), any())
    ).thenReturn(Future.failed(new RuntimeException("oops")))

    nihService
      .processExternalCredsMessage(
        ReceivedMessage[ExternalCredsMessage](ExternalCredsMessage(provider, userId), None, Instant.now(), ackHandler)
      )
      .unsafeRunSync()

    verify(ackHandler).nack()
  }

  private def mockSamUsers(): Unit = {
    when(samDao.overwriteGroupMembers(any(), any(), any())(any())).thenReturn(Future.successful(()))
    when(samDao.listGroups(any[WithAccessToken])).thenAnswer { args =>
      Future {
        val userInfo = args.getArgument(0).asInstanceOf[WithAccessToken]
        if (userInfo.accessToken.token.equals(adminAccessToken)) {
          samGroupMemberships.keys
            .map(groupName => FireCloudManagedGroupMembership(groupName, groupName + "@firecloud.org", "member"))
            .toList
        }
        val samUser = accessTokenToUser.get(userInfo.accessToken.token)
        samUser
          .map(
            samUserToGroups(_)
              .map(groupName => FireCloudManagedGroupMembership(groupName, groupName + "@firecloud.com", "member"))
              .toList
          )
          .getOrElse(List.empty)
      }
    }
    when(samDao.addGroupMember(any(), any(), any())(any())).thenReturn(Future.successful(()))
    when(samDao.removeGroupMember(any(), any(), any())(any())).thenReturn(Future.successful(()))
    when(samDao.isGroupMember(any[WorkbenchGroupName], any[UserInfo])).thenAnswer(args =>
      Future {
        val groupName = args.getArgument(0).asInstanceOf[WorkbenchGroupName]
        val userInfo = args.getArgument(1).asInstanceOf[UserInfo]
        samGroupMemberships.get(groupName.value).exists(_.exists(_.value == userInfo.id))
      }
    )
    when(samDao.createGroup(any[WorkbenchGroupName])(ArgumentMatchers.eq(UserInfo(adminAccessToken, ""))))
      .thenReturn(Future.successful(()))
    when(samDao.getUsersForIds(any[Seq[WorkbenchUserId]])(ArgumentMatchers.eq(UserInfo(adminAccessToken, ""))))
      .thenAnswer { args =>
        val userIds = args.getArgument(0).asInstanceOf[Seq[WorkbenchUserId]]
        Future.successful(
          samUsers
            .filter(user => userIds.contains(WorkbenchUserId(user.id.value)))
            .map(user => WorkbenchUserInfo(user.id.value, user.email.value))
        )
      }
    when(samDao.getGroupEmail(ArgumentMatchers.eq(WorkbenchGroupName("dbgap_phs002409_c1")))(any())).thenReturn(
      Future.successful(
        dbGapGroupEmail
      )
    )

  }

  private def mockThurloeUsers(): Unit = {
    when(thurloeDao.getAllKVPs(any[String], any[WithAccessToken])).thenAnswer(args =>
      Future {
        val userId = WorkbenchUserId(args.getArgument(0).asInstanceOf[String])
        val user = idToSamUser(userId)
        val linkedEraAccount = linkedAccountsBySamUserId.get(userId)
        Some(
          ProfileWrapper(
            userId.value,
            List(
              FireCloudKeyValue(Some("contactEmail"), Some(user.email.value)),
              FireCloudKeyValue(Some("linkedNihUsername"), linkedEraAccount.map(_.linkedExternalId)),
              FireCloudKeyValue(Some("linkExpireTime"), linkedEraAccount.map(_.linkExpireTime.getMillis.toString))
            )
          )
        )
      }
    )
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("linkedNihUsername")))
      .thenReturn(Future.successful(linkedAccountsBySamUserId.map(tup => (tup._1.value, tup._2.linkedExternalId))))
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("linkExpireTime"))).thenReturn(
      Future.successful(
        linkedAccountsBySamUserId.map(tup => (tup._1.value, (tup._2.linkExpireTime.getMillis / 1000).toString))
      )
    )
    when(thurloeDao.saveKeyValues(any[UserInfo], any[Map[String, String]])).thenReturn(Future.successful(Success(())))
    when(thurloeDao.saveKeyValues(any[String], any[WithAccessToken], any[Map[String, String]]))
      .thenReturn(Future.successful(Success(())))
    when(thurloeDao.deleteKeyValue(any[String], any[String], any[WithAccessToken]))
      .thenReturn(Future.successful(Success(())))
  }

  private def mockGoogleServicesDAO(): Unit = {
    when(googleDao.getBucketObjectAsInputStream(ArgumentMatchers.eq(FireCloudConfig.Nih.whitelistBucket), any[String]))
      .thenAnswer { args =>
        val filename = args.getArgument(1).asInstanceOf[String]
        val nihUsernames = filename match {
          case "tcga-whitelist.txt" =>
            Seq(userTcgaAndTargetLinkedAccount.linkedExternalId, userTcgaOnlyLinkedAccount.linkedExternalId)
          case "target-whitelist.txt" =>
            Seq(userTcgaAndTargetLinkedAccount.linkedExternalId, userTargetOnlyLinkedAccount.linkedExternalId)
          case "dbgap_phs002409_c1_whitelist.txt" =>
            Seq(userDbGapLinkedAccount.linkedExternalId)
          case "broken-whitelist.txt" => Seq.empty
        }
        new ByteArrayInputStream(nihUsernames.mkString("\n").getBytes(StandardCharsets.UTF_8))
      }
    when(googleDao.getAdminUserAccessToken).thenReturn(adminAccessToken)
  }

  private def genSamUser(): SamUser =
    SamUser(
      WorkbenchUserId(Random.nextInt().toString),
      Some(GoogleSubjectId(Random.nextInt().toString)),
      WorkbenchEmail(UUID.randomUUID().toString + "@email.com"),
      Some(AzureB2CId(UUID.randomUUID().toString)),
      enabled = true,
      Instant.now(),
      Some(Instant.now()),
      Instant.now()
    )

}
