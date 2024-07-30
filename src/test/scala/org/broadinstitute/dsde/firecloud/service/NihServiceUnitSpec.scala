package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.dataaccess.{ExternalCredsDAO, GoogleServicesDAO, SamDAO, ShibbolethDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, FireCloudManagedGroupMembership, JWTWrapper, LinkedEraAccount, ManagedGroupRoles, NihLink, ProfileWrapper, SamUser, UserInfo, WithAccessToken, WorkbenchUserInfo}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.joda.time.DateTime
import org.mockito.{ArgumentMatchers, Mockito}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, times, verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.{KeyPairGenerator, PrivateKey}
import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Random, Success}

class NihServiceUnitSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  val samDao = mock[SamDAO]
  val thurloeDao = mock[ThurloeDAO]
  val googleDao = mock[GoogleServicesDAO]
  val shibbolethDao = mock[ShibbolethDAO]
  val ecmDao = mock[ExternalCredsDAO]

  // build the service instance we'll use for tests
  val nihService = new NihService(samDao, thurloeDao, googleDao, shibbolethDao, ecmDao)

  val userNoLinkedAccount = genSamUser();
  val userNoAllowlists = genSamUser()
  val userTcgaAndTarget = genSamUser();
  val userTcgaOnly = genSamUser();
  val userTargetOnly = genSamUser();

  var userNoAllowlistsLinkedAccount = LinkedEraAccount(userNoAllowlists.id, "nihUsername1", new DateTime().plusDays(30))
  var userTcgaAndTargetLinkedAccount = LinkedEraAccount(userTcgaAndTarget.id, "nihUsername2", new DateTime().plusDays(30))
  var userTcgaOnlyLinkedAccount = LinkedEraAccount(userTcgaOnly.id, "nihUsername3", new DateTime().plusDays(30))
  var userTargetOnlyLinkedAccount = LinkedEraAccount(userTargetOnly.id, "nihUsername4", new DateTime().plusDays(30))

  val samUsers = Seq(userNoLinkedAccount, userNoAllowlists, userTcgaAndTarget, userTcgaOnly, userTargetOnly)
  val linkedAccounts = Seq(userNoAllowlistsLinkedAccount, userTcgaAndTargetLinkedAccount, userTcgaOnlyLinkedAccount, userTargetOnlyLinkedAccount)

  val idToSamUser = samUsers.groupBy(_.id).view.mapValues(_.head).toMap

  val linkedAccountsBySamUserId = Map(
    userNoAllowlists.id -> userNoAllowlistsLinkedAccount,
    userTcgaAndTarget.id -> userTcgaAndTargetLinkedAccount,
    userTcgaOnly.id -> userTcgaOnlyLinkedAccount,
    userTargetOnly.id -> userTargetOnlyLinkedAccount
  )

  val linkedAccountsByExternalId = Map(
    userNoAllowlistsLinkedAccount.linkedExternalId -> userNoAllowlistsLinkedAccount,
    userTcgaAndTargetLinkedAccount.linkedExternalId -> userTcgaAndTargetLinkedAccount,
    userTcgaOnlyLinkedAccount.linkedExternalId -> userTcgaOnlyLinkedAccount,
    userTargetOnlyLinkedAccount.linkedExternalId -> userTargetOnlyLinkedAccount
  )

  val samUserToGroups = {
    Map(
      userNoLinkedAccount.id -> Set(),
      userNoAllowlists.id -> Set(),
      userTcgaAndTarget.id -> Set("TCGA-dbGaP-Authorized", "TARGET-dbGaP-Authorized"),
      userTcgaOnly.id -> Set("TCGA-dbGaP-Authorized"),
      userTargetOnly.id -> Set("TARGET-dbGaP-Authorized")
    )
  }

  val samGroupMemberships = {
    Map(
      "TCGA-dbGaP-Authorized" -> Set(userTcgaAndTarget.id, userTcgaOnly.id),
      "TARGET-dbGaP-Authorized" -> Set(userTcgaAndTarget.id, userTargetOnly.id),
      "this-doesnt-matter" -> Set.empty
    )
  }

  val accessTokenToUser = {
    Map(
      UUID.randomUUID().toString -> userNoLinkedAccount.id,
        UUID.randomUUID().toString -> userNoAllowlists.id,
        UUID.randomUUID().toString -> userTcgaAndTarget.id,
        UUID.randomUUID().toString -> userTcgaOnly.id,
        UUID.randomUUID().toString -> userTargetOnly.id
    )
  }

  val userToAccessToken = accessTokenToUser.map(_.swap)
  val adminAccessToken = UUID.randomUUID().toString

  override def beforeEach(): Unit = {
    Mockito.reset(thurloeDao, ecmDao, googleDao, samDao)
    mockSamUsers()
    mockGoogleServicesDAO()
  }

  "getNihStatus" should "prefer ECM over Thurloe" in {
    mockEcmUsers()
    val user = userTcgaAndTarget
    val userInfo = UserInfo(userToAccessToken(user.id), userTcgaAndTarget.id)
    val nihStatus = Await.result(nihService.getNihStatus(userInfo), Duration.Inf).asInstanceOf[PerRequest.RequestComplete[NihStatus]].response
    nihStatus.linkedNihUsername shouldBe Some(linkedAccountsBySamUserId(userInfo.id).linkedExternalId)
    verifyNoInteractions(thurloeDao)
    verify(ecmDao).getLinkedAccount(userInfo)
  }

  it should "talk to Thurloe if nothing link is found in ECM" in {
    mockThurloeUsers()
    when(ecmDao.getLinkedAccount(any[UserInfo])).thenReturn(Future.successful(None))
    val user = userTcgaAndTarget
    val userInfo = UserInfo(userToAccessToken(user.id), userTcgaAndTarget.id)
    val nihStatus = Await.result(nihService.getNihStatus(userInfo), Duration.Inf).asInstanceOf[PerRequest.RequestComplete[NihStatus]].response
    nihStatus.linkedNihUsername shouldBe Some(linkedAccountsBySamUserId(userInfo.id).linkedExternalId)
    verify(thurloeDao).getAllKVPs(user.id, userInfo)
  }

  it should "return None if no linked account is found" in {
    when(thurloeDao.getAllKVPs(any[String], any[WithAccessToken])).thenReturn(Future.successful(None))
    when(ecmDao.getLinkedAccount(any[UserInfo])).thenReturn(Future.successful(None))
    val user = userNoLinkedAccount
    val userInfo = UserInfo(userToAccessToken(user.id), userNoLinkedAccount.id)
    val nihStatus = Await.result(nihService.getNihStatus(userInfo), Duration.Inf).asInstanceOf[PerRequest.RequestComplete[StatusCode]].response
    nihStatus should be(StatusCodes.NotFound)
  }

  it should "return None if a user if found in Thurloe, but no linkedNihUsername exists" in {
    when(thurloeDao.getAllKVPs(any[String], any[WithAccessToken]))
      .thenReturn(Future.successful(Some(ProfileWrapper(userNoLinkedAccount.id, List(FireCloudKeyValue(Some("email"), Some(userNoLinkedAccount.email)))))))
    when(ecmDao.getLinkedAccount(any[UserInfo])).thenReturn(Future.successful(None))
    val user = userNoLinkedAccount
    val userInfo = UserInfo(userToAccessToken(user.id), userNoLinkedAccount.id)
    val nihStatus = Await.result(nihService.getNihStatus(userInfo), Duration.Inf).asInstanceOf[PerRequest.RequestComplete[StatusCode]].response
    nihStatus should be(StatusCodes.NotFound)
  }

  private def verifyTargetGroupSynced(): Unit = {
    val emailsToSync = Set(WorkbenchEmail(userTcgaAndTarget.email), WorkbenchEmail(userTargetOnly.email))
    val nihStatus = Await.result(nihService.syncWhitelistAllUsers("TARGET"), Duration.Inf).asInstanceOf[PerRequest.RequestComplete[StatusCode]].response

    nihStatus should be(StatusCodes.NoContent)
    verify(googleDao, never()).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "tcga-whitelist.txt")
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "target-whitelist.txt")
    verify(samDao, times(1)).overwriteGroupMembers(
      ArgumentMatchers.eq(WorkbenchGroupName("TARGET-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.argThat((list: List[WorkbenchEmail]) => list.toSet.equals(emailsToSync)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
  }

  "syncWhitelistAllUsers" should "sync all users for a single allowlist from ECM" in {
    mockEcmUsers()
    when(thurloeDao.getAllUserValuesForKey(any[String])).thenReturn(Future.successful(Map.empty))

    verifyTargetGroupSynced()
  }

  it should "sync all users for a single allowlist from Thurloe" in {
    mockThurloeUsers()
    when(ecmDao.getActiveLinkedEraAccounts(any[UserInfo])).thenReturn(Future.successful(Seq.empty))

    verifyTargetGroupSynced()
  }

  it should "sync all users by combining responses from ECM and Thurloe if they contain the same users" in {
    mockEcmUsers()
    mockThurloeUsers()

    verifyTargetGroupSynced()
  }

  it should "sync all users by combining responses from ECM and Thurloe if they contain different users" in {
    when(ecmDao.getActiveLinkedEraAccounts(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))).thenReturn(Future.successful(Seq(userTargetOnlyLinkedAccount)))
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("email")))
      .thenReturn(Future.successful(samUsers.filter(u => u.id != userTargetOnly.id).map(user => user.id -> user.email).toMap))
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("linkedNihUsername"))).thenReturn(Future.successful(linkedAccountsBySamUserId.removed(userTargetOnlyLinkedAccount.userId).view.mapValues(_.linkedExternalId).toMap))
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("linkExpireTime"))).thenReturn(Future.successful(linkedAccountsBySamUserId.removed(userTargetOnlyLinkedAccount.userId).view.mapValues(_.linkExpireTime.getMillis.toString).toMap))

    verifyTargetGroupSynced()
  }

  it should "respond with NOT FOUND if no allowlist is found" in {
    val nihStatus = Await.result(nihService.syncWhitelistAllUsers("NOT_FOUND"), Duration.Inf).asInstanceOf[PerRequest.RequestComplete[StatusCode]].response

    nihStatus should be(StatusCodes.NotFound)

  }

   it should "recover from a Sam API Exception with a FirecloudException" in {
     val errorMessage = "Oops :("
     Mockito.reset(samDao)
     mockEcmUsers()
     mockThurloeUsers()
     when(samDao.getUsersForIds(any[Seq[WorkbenchUserId]])(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))).thenAnswer(args => {
       val userIds = args.getArgument(0).asInstanceOf[Seq[WorkbenchUserId]]
       Future.successful(samUsers.filter(user => userIds.contains(WorkbenchUserId(user.id))).map(user => WorkbenchUserInfo(user.id, user.email)))
     })
     when(samDao.overwriteGroupMembers(any(), any(), any())(any())).thenReturn(Future.failed(new RuntimeException(errorMessage)))
     when(samDao.listGroups(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))).thenReturn(Future.successful(samGroupMemberships.keys.map(groupName => FireCloudManagedGroupMembership(groupName, groupName + "@firecloud.org", "member")).toList))
     when(samDao.createGroup(any[WorkbenchGroupName])(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))).thenReturn(Future.successful())

     val ex = intercept[FireCloudException] {
       Await.result(nihService.syncWhitelistAllUsers("TARGET"), Duration.Inf)
     }
     ex.getMessage should include(errorMessage)
   }

  "syncAllNihWhitelistsAllUsers" should "sync all allowlists for all users" in {
    mockEcmUsers()
    when(thurloeDao.getAllUserValuesForKey(any[String])).thenReturn(Future.successful(Map.empty))

    val targetEmailsToSync = Set(WorkbenchEmail(userTcgaAndTarget.email), WorkbenchEmail(userTargetOnly.email))
    val tcgaUsersToSync = Set(WorkbenchEmail(userTcgaAndTarget.email), WorkbenchEmail(userTcgaOnly.email))
    val nihStatus = Await.result(nihService.syncAllNihWhitelistsAllUsers(), Duration.Inf).asInstanceOf[PerRequest.RequestComplete[StatusCode]].response

    nihStatus should be(StatusCodes.NoContent)
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "tcga-whitelist.txt")
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "target-whitelist.txt")
    verify(samDao, times(1)).overwriteGroupMembers(
      ArgumentMatchers.eq(WorkbenchGroupName("TARGET-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.argThat((list: List[WorkbenchEmail]) => list.toSet.equals(targetEmailsToSync)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(samDao, times(1)).overwriteGroupMembers(
      ArgumentMatchers.eq(WorkbenchGroupName("TCGA-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.argThat((list: List[WorkbenchEmail]) => list.toSet.equals(tcgaUsersToSync)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
  }

  "updateNihLinkAndSyncSelf" should "decode a JWT from Shibboleth and sync allowlists for a user" in {
    mockShibbolethDAO()
    mockEcmUsers()
    mockThurloeUsers()
    val user = userTcgaOnly
    val userInfo = UserInfo(user.email, OAuth2BearerToken(user.id), Instant.now().plusSeconds(60).getEpochSecond, user.id)
    val linkedAccount = userTcgaOnlyLinkedAccount
    val jwt = jwtForUser(linkedAccount)
    val (statusCode, nihStatus) = Await.result(nihService.updateNihLinkAndSyncSelf(userInfo, jwt), Duration.Inf).asInstanceOf[PerRequest.RequestComplete[(StatusCode, NihStatus)]].response

    nihStatus.linkedNihUsername should be(Some(linkedAccount.linkedExternalId))
    nihStatus.linkExpireTime should be(Some(linkedAccount.linkExpireTime.getMillis / 1000L))
    nihStatus.datasetPermissions should be(Set(
      NihDatasetPermission("BROKEN", authorized = false),
      NihDatasetPermission("TARGET", authorized = false),
      NihDatasetPermission("TCGA", authorized = true)))

    statusCode should be(StatusCodes.OK)
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "tcga-whitelist.txt")
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "target-whitelist.txt")
    verify(samDao, times(1)).removeGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("TARGET-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(samDao, times(1)).addGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("TCGA-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(samDao, never()).addGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("this-doesnt-matter")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
  }

  it should "continue, but return an error of ECM returns an error" in {
    mockShibbolethDAO()
    mockThurloeUsers()
    when(ecmDao.putLinkedEraAccount(any[LinkedEraAccount])(any[WithAccessToken])).thenReturn(Future.failed(new RuntimeException("ECM is down")))

    val user = userTcgaOnly
    val userInfo = UserInfo(user.email, OAuth2BearerToken(user.id), Instant.now().plusSeconds(60).getEpochSecond, user.id)
    val linkedAccount = userTcgaOnlyLinkedAccount
    val jwt = jwtForUser(linkedAccount)
    val (statusCode, errorReport) = Await.result(nihService.updateNihLinkAndSyncSelf(userInfo, jwt), Duration.Inf).asInstanceOf[PerRequest.RequestComplete[(StatusCode, ErrorReport)]].response

    errorReport.message should include("Error updating NIH link")
    statusCode should be(StatusCodes.InternalServerError)

    verify(thurloeDao, times(1)).saveKeyValues(userInfo, NihLink(linkedAccount).propertyValueMap)
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "tcga-whitelist.txt")
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "target-whitelist.txt")
    verify(samDao, times(1)).removeGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("TARGET-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(samDao, times(1)).addGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("TCGA-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(samDao, never()).addGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("this-doesnt-matter")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
  }

  it should "continue, but return an error of Thurloe returns an error" in {
    mockShibbolethDAO()
    mockEcmUsers()
    when(thurloeDao.saveKeyValues(any[UserInfo], any[Map[String, String]])).thenReturn(Future.successful(Failure(new RuntimeException("Thurloe is down"))))

    val user = userTcgaOnly
    val userInfo = UserInfo(user.email, OAuth2BearerToken(user.id), Instant.now().plusSeconds(60).getEpochSecond, user.id)
    val linkedAccount = userTcgaOnlyLinkedAccount
    val jwt = jwtForUser(linkedAccount)
    val (statusCode, errorReport) = Await.result(nihService.updateNihLinkAndSyncSelf(userInfo, jwt), Duration.Inf).asInstanceOf[PerRequest.RequestComplete[(StatusCode, ErrorReport)]].response

    errorReport.message should include("Error updating NIH link")
    statusCode should be(StatusCodes.InternalServerError)

    // Tokens from Shibboleth are to the second, not millisecond
    var expectedLinkedAccount = linkedAccount.copy(linkExpireTime = linkedAccount.linkExpireTime.minusMillis(linkedAccount.linkExpireTime.getMillisOfSecond))

    verify(ecmDao, times(1)).putLinkedEraAccount(ArgumentMatchers.eq(expectedLinkedAccount))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "tcga-whitelist.txt")
    verify(googleDao, times(1)).getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, "target-whitelist.txt")
    verify(samDao, times(1)).removeGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("TARGET-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(samDao, times(1)).addGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("TCGA-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(samDao, never()).addGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("this-doesnt-matter")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
  }

  "unlinkNihAccountAndSyncSelf" should "remove links from ECM and Thurloe, and sync allowlists" in {
    mockEcmUsers()
    mockThurloeUsers()

    val user = userTcgaOnly
    val userInfo = UserInfo(user.email, OAuth2BearerToken(user.id), Instant.now().plusSeconds(60).getEpochSecond, user.id)
    Await.result(nihService.unlinkNihAccountAndSyncSelf(userInfo), Duration.Inf)

    verify(samDao, times(1)).removeGroupMember(
      ArgumentMatchers.eq(WorkbenchGroupName("TCGA-dbGaP-Authorized")),
      ArgumentMatchers.eq(ManagedGroupRoles.Member),
      ArgumentMatchers.eq(WorkbenchEmail(user.email)))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(ecmDao, times(1)).deleteLinkedEraAccount(ArgumentMatchers.eq(userInfo))(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))
    verify(thurloeDao, times(1)).deleteKeyValue(user.id, "linkedNihUsername", userInfo)
    verify(thurloeDao, times(1)).deleteKeyValue(user.id, "linkExpireTime", userInfo)

  }

  private def mockSamUsers(): Unit = {
    when(samDao.overwriteGroupMembers(any(), any(), any())(any())).thenReturn(Future.successful())
    when(samDao.listGroups(any[WithAccessToken])).thenAnswer(args => {
      Future {
        val userInfo = args.getArgument(0).asInstanceOf[WithAccessToken]
        if (userInfo.accessToken.token.equals(adminAccessToken)) {
          samGroupMemberships.keys.map(groupName => FireCloudManagedGroupMembership(groupName, groupName + "@firecloud.org", "member")).toList
        }
        val samUser = accessTokenToUser.get(userInfo.accessToken.token)
        samUser.map(samUserToGroups(_).map(groupName => FireCloudManagedGroupMembership(groupName, groupName + "@firecloud.com", "member")).toList)
          .getOrElse(List.empty)
      }
    })
    when(samDao.addGroupMember(any(), any(), any())(any())).thenReturn(Future.successful())
    when(samDao.removeGroupMember(any(), any(), any())(any())).thenReturn(Future.successful())
    when(samDao.isGroupMember(any[WorkbenchGroupName], any[UserInfo])).thenAnswer(args => Future {
      val groupName = args.getArgument(0).asInstanceOf[WorkbenchGroupName]
      val userInfo = args.getArgument(1).asInstanceOf[UserInfo]
      samGroupMemberships.get(groupName.value).exists(_.exists(_ == userInfo.id))
    })
    when(samDao.createGroup(any[WorkbenchGroupName])(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))).thenReturn(Future.successful())
    when(samDao.getUsersForIds(any[Seq[WorkbenchUserId]])(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))).thenAnswer(args => {
      val userIds = args.getArgument(0).asInstanceOf[Seq[WorkbenchUserId]]
      Future.successful(samUsers.filter(user => userIds.contains(WorkbenchUserId(user.id))).map(user => WorkbenchUserInfo(user.id, user.email)))
    })

  }

  private def mockEcmUsers(): Unit = {
    when(ecmDao.getLinkedAccount(any[UserInfo])).thenAnswer(args => {
      val userInfo = args.getArgument(0).asInstanceOf[UserInfo]
      Future.successful(linkedAccountsBySamUserId.get(userInfo.id))
    })
    when(ecmDao.putLinkedEraAccount(any[LinkedEraAccount])(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))).thenReturn(Future.successful())
    when(ecmDao.deleteLinkedEraAccount(any[UserInfo])(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))).thenReturn(Future.successful())

    when(ecmDao.getLinkedEraAccountForUsername(any[String])(ArgumentMatchers.eq(UserInfo(adminAccessToken, "")))).thenAnswer(args => {
      val externalId = args.getArgument(0).asInstanceOf[String]
      Future.successful(linkedAccountsByExternalId.get(externalId))
    })
    when(ecmDao.getActiveLinkedEraAccounts(ArgumentMatchers.eq(UserInfo(adminAccessToken, ""))))
      .thenReturn(Future.successful(linkedAccountsBySamUserId.values.toSeq))
  }

  private def mockThurloeUsers(): Unit = {
    when(thurloeDao.getAllKVPs(any[String], any[WithAccessToken])).thenAnswer(args => Future {
      val userId = args.getArgument(0).asInstanceOf[String]
      val user = idToSamUser(userId)
      val linkedEraAccount = linkedAccountsBySamUserId.get(userId)
      Some(ProfileWrapper(userId, List(
        FireCloudKeyValue(Some("contactEmail"), Some(user.email)),
        FireCloudKeyValue(Some("linkedNihUsername"), linkedEraAccount.map(_.linkedExternalId)),
        FireCloudKeyValue(Some("linkExpireTime"), linkedEraAccount.map(_.linkExpireTime.getMillis.toString))
      )))
    })
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("email")))
      .thenReturn(Future.successful(samUsers.map(user => user.id -> user.email).toMap))
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("linkedNihUsername"))).thenReturn(Future.successful(linkedAccountsBySamUserId.view.mapValues(_.linkedExternalId).toMap))
    when(thurloeDao.getAllUserValuesForKey(ArgumentMatchers.eq("linkExpireTime"))).thenReturn(Future.successful(linkedAccountsBySamUserId.view.mapValues(_.linkExpireTime.getMillis.toString).toMap))
    when(thurloeDao.saveKeyValues(any[UserInfo], any[Map[String, String]])).thenReturn(Future.successful(Success()))
    when(thurloeDao.saveKeyValues(any[String], any[WithAccessToken], any[Map[String, String]])).thenReturn(Future.successful(Success()))
    when(thurloeDao.deleteKeyValue(any[String], any[String], any[WithAccessToken])).thenReturn(Future.successful(Success()))
  }

  private def mockGoogleServicesDAO(): Unit = {
    when(googleDao.getBucketObjectAsInputStream(ArgumentMatchers.eq(FireCloudConfig.Nih.whitelistBucket), any[String])).thenAnswer(args => {
      val filename = args.getArgument(1).asInstanceOf[String]
      val nihUsernames = filename match {
        case "tcga-whitelist.txt" => Seq(userTcgaAndTargetLinkedAccount.linkedExternalId, userTcgaOnlyLinkedAccount.linkedExternalId)
        case "target-whitelist.txt" => Seq(userTcgaAndTargetLinkedAccount.linkedExternalId, userTargetOnlyLinkedAccount.linkedExternalId)
        case "broken-whitelist.txt" => Seq.empty
      }
      new ByteArrayInputStream(nihUsernames.mkString("\n").getBytes(StandardCharsets.UTF_8))
    })
    when(googleDao.getAdminUserAccessToken).thenReturn(adminAccessToken)
  }

  private def mockShibbolethDAO(): Unit = {
    when(shibbolethDao.getPublicKey()).thenReturn(Future.successful(pubKey))
  }

  val keypairGen = KeyPairGenerator.getInstance("RSA")
  keypairGen.initialize(1024)
  val keypair = keypairGen.generateKeyPair()

  val privKey: PrivateKey = keypair.getPrivate
  val pubKey: String = s"-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder.encodeToString(keypair.getPublic.getEncoded)}\n-----END PUBLIC KEY-----"

  private def jwtForUser(linkedEraAccount: LinkedEraAccount): JWTWrapper = {
    val expiresInTheFuture: Long = linkedEraAccount.linkExpireTime.getMillis / 1000L
    val issuedAt = Instant.ofEpochMilli(linkedEraAccount.linkExpireTime.minusDays(30).getMillis).getEpochSecond
    val validStr = Jwt.encode(
      JwtClaim(s"""{"eraCommonsUsername": "${linkedEraAccount.linkedExternalId}"}""").issuedAt(issuedAt).expiresAt(expiresInTheFuture),
      privKey,
      JwtAlgorithm.RS256)
    JWTWrapper(validStr)
  }

  private def genSamUser(): SamUser = {
    SamUser(Random.nextInt().toString, Random.nextInt().toString, UUID.randomUUID().toString + "@email.com", UUID.randomUUID().toString, true, "1", Instant.now(), Some(Instant.now()), Instant.now())
  }



}
