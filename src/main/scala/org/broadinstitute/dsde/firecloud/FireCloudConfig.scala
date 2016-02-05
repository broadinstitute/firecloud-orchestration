package org.broadinstitute.dsde.firecloud

import com.typesafe.config.ConfigFactory

object FireCloudConfig {
  private val config = ConfigFactory.load()

  object Auth {
    lazy val googleClientId = sys.env.get("GOOGLE_CLIENT_ID").get
    lazy val googleSecretJson = sys.env.get("GOOGLE_SECRET_JSON").get
    lazy val adminRefreshToken = sys.env.get("ADMIN_REFRESH_TOKEN").get
    lazy val refreshTokenSecretJson = sys.env.get("REFRESH_TOKEN_SECRET_JSON").get
    lazy val pemFile = sys.env.get("ORCHESTRATION_PEM").get
    lazy val pemFileClientId = sys.env.get("ORCHESTRATION_PEM_CLIENT_ID").get
    lazy val swaggerRealm = sys.env.get("SWAGGER_REALM").get
  }

  object HttpConfig {
    private val httpConfig = config.getConfig("http")
    lazy val interface = httpConfig.getString("interface")
    lazy val port = httpConfig.getInt("port")
    lazy val timeoutSeconds = httpConfig.getLong("timeoutSeconds")
  }

  object Agora {
    private val methods = config.getConfig("methods")
    lazy val baseUrl = sys.env.get("AGORA_URL_ROOT").get
    lazy val authPrefix = methods.getString("authPrefix")
    lazy val authUrl = baseUrl + authPrefix
  }

  object Rawls {
    private val workspace = config.getConfig("workspace")
    lazy val model = "/" + workspace.getString("model")
    lazy val baseUrl = sys.env.get("RAWLS_URL_ROOT").get
    lazy val authPrefix = workspace.getString("authPrefix")
    lazy val authUrl = baseUrl + authPrefix
    lazy val workspacesPath = workspace.getString("workspacesPath")
    lazy val workspacesUrl = authUrl + workspacesPath
    lazy val entitiesPath = workspace.getString("entitiesPath")
    lazy val importEntitiesPath = workspace.getString("importEntitiesPath")
    lazy val workspacesEntitiesCopyPath = workspace.getString("workspacesEntitiesCopyPath")
    lazy val workspacesEntitiesCopyUrl = authUrl + workspacesEntitiesCopyPath
    lazy val submissionsPath = workspace.getString("submissionsPath")
    lazy val submissionsUrl = authUrl + submissionsPath
    lazy val submissionsIdPath = workspace.getString("submissionsIdPath")
    lazy val overwriteGroupMembershipPath = workspace.getString("overwriteGroupMembershipPath")

    def entityPathFromWorkspace(namespace: String, name: String) = authUrl + entitiesPath.format(namespace, name)
    def importEntitiesPathFromWorkspace(namespace: String, name: String) = authUrl + importEntitiesPath.format(namespace, name)
    def overwriteGroupMembershipUrlFromGroupName(groupName: String) = authUrl + overwriteGroupMembershipPath.format(groupName)
  }

  object Thurloe {
    private val profile = config.getConfig("userprofile")
    lazy val baseUrl = sys.env.get("THURLOE_URL_ROOT").get
    lazy val authPrefix = profile.getString("authPrefix")
    lazy val authUrl = baseUrl + authPrefix
    lazy val setKey = profile.getString("setKey")
    lazy val get = profile.getString("get")
    lazy val getAll = profile.getString("getAll")
    lazy val getQuery = profile.getString("getQuery")
    lazy val delete = profile.getString("delete")
  }

  object FireCloud {
    lazy val baseUrl = sys.env.get("FIRECLOUD_URL_ROOT").get
  }

  object Shibboleth {
    lazy val signingKey = sys.env.get("JWT_SIGNING_KEY").get
  }

  object Nih {
    private val nih = config.getConfig("nih")
    lazy val whitelistBucket = sys.env.get("NIH_WHITELIST_BUCKET").get
    lazy val whitelistFile = nih.getString("whitelistFile")
    lazy val rawlsGroupName = nih.getString("rawlsGroupName")
  }

}
