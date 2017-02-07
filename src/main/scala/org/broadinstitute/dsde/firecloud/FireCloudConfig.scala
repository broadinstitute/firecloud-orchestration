package org.broadinstitute.dsde.firecloud

import com.typesafe.config.ConfigFactory
import spray.http.Uri.{Authority, Host}

object FireCloudConfig {
  private val config = ConfigFactory.load()

  object Auth {
    private val auth = config.getConfig("auth")
    val googleClientId = auth.getString("googleClientId")
    val googleSecretJson = auth.getString("googleSecretsJson")
    val pemFile = auth.getString("pemFile")
    val pemFileClientId = auth.getString("pemFileClientId")
    val rawlsPemFile = auth.getString("rawlsPemFile")
    val rawlsPemFileClientId = auth.getString("rawlsPemFileClientId")
    val swaggerRealm = auth.getString("swaggerRealm")
  }

  object HttpConfig {
    private val httpConfig = config.getConfig("http")
    val interface = httpConfig.getString("interface")
    val port = httpConfig.getInt("port")
    val timeoutSeconds = httpConfig.getLong("timeoutSeconds")
  }

  object Agora {
    private val methods = config.getConfig("methods")
    private val agora = config.getConfig("agora")
    val baseUrl = agora.getString("baseUrl")
    val authPrefix = methods.getString("authPrefix")
    val authUrl = baseUrl + authPrefix
  }

  object Rawls {
    private val workspace = config.getConfig("workspace")
    private val rawls = config.getConfig("rawls")
    val model = "/" + workspace.getString("model")
    val baseUrl = rawls.getString("baseUrl")
    val authPrefix = workspace.getString("authPrefix")
    val authUrl = baseUrl + authPrefix
    val workspacesPath = workspace.getString("workspacesPath")
    val workspacesUrl = authUrl + workspacesPath
    val entitiesPath = workspace.getString("entitiesPath")
    val entityQueryPath = workspace.getString("entityQueryPath")
    val importEntitiesPath = workspace.getString("importEntitiesPath")
    val workspacesEntitiesCopyPath = workspace.getString("workspacesEntitiesCopyPath")
    val workspacesEntitiesCopyUrl = authUrl + workspacesEntitiesCopyPath
    val submissionsPath = workspace.getString("submissionsPath")
    val submissionsUrl = authUrl + submissionsPath
    val submissionsIdPath = workspace.getString("submissionsIdPath")
    val overwriteGroupMembershipPath = workspace.getString("overwriteGroupMembershipPath")
    val submissionQueueStatusPath = workspace.getString("submissionQueueStatusPath")
    val submissionQueueStatusUrl = authUrl + submissionQueueStatusPath

    def entityPathFromWorkspace(namespace: String, name: String) = authUrl + entitiesPath.format(namespace, name)
    def entityQueryPathFromWorkspace(namespace: String, name: String) = authUrl + entityQueryPath.format(namespace, name)
    def importEntitiesPathFromWorkspace(namespace: String, name: String) = authUrl + importEntitiesPath.format(namespace, name)
    def overwriteGroupMembershipUrlFromGroupName(groupName: String) = authUrl + overwriteGroupMembershipPath.format(groupName)
  }

  object Thurloe {
    private val profile = config.getConfig("userprofile")
    private val thurloe = config.getConfig("thurloe")
    val baseUrl = thurloe.getString("baseUrl")
    val authPrefix = profile.getString("authPrefix")
    val authUrl = baseUrl + authPrefix
    val setKey = profile.getString("setKey")
    val get = profile.getString("get")
    val getAll = profile.getString("getAll")
    val getQuery = profile.getString("getQuery")
    val delete = profile.getString("delete")
    val postNotify = profile.getString("postNotify")
  }

  object FireCloud {
    private val firecloud = config.getConfig("firecloud")
    val baseUrl = firecloud.getString("baseUrl")
    val fireCloudId = firecloud.getString("fireCloudId")
    val fireCloudPortalUrl = firecloud.getString("portalUrl")
  }

  object Shibboleth {
    private val shibboleth = config.getConfig("shibboleth")
    val signingKey = shibboleth.getString("jwtSigningKey")
  }

  object Nih {
    private val nih = config.getConfig("nih")
    val whitelistBucket = nih.getString("whitelistBucket")
    val whitelistFile = nih.getString("whitelistFile")
    val rawlsGroupName = nih.getString("rawlsGroupName")
  }

  object Notification {
    private val thurloe = config.getConfig("thurloe")
    val activationTemplateId = thurloe.getString("activationTemplateId")
    val workspaceAddedTemplateId = thurloe.getString("workspaceAddedTemplateId")
    val workspaceRemovedTemplateId = thurloe.getString("workspaceRemovedTemplateId")
    val workspaceInvitedTemplateId = thurloe.getString("workspaceInvitedTemplateId")
  }

  object ElasticSearch {
    private val elasticsearch = config.getConfig("elasticsearch")
    val servers: Seq[Authority] = (elasticsearch.getString("urls").split(',') map { hostport =>
      val hp = hostport.split(':')
      Authority(Host(hp(0)), hp(1).toInt)
    })
    val indexName = elasticsearch.getString("index")
    val discoverGroupNames = elasticsearch.getStringList("discoverGroupNames")
  }

  object GoogleCloud {
    private val googlecloud = config.getConfig("googlecloud")
    val priceListUrl = googlecloud.getString("priceListUrl")
  }

  object Duos {
    private val duos = config.getConfig("duos")
    val baseUrl = duos.getString("baseUrl")
  }
}
