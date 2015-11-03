package org.broadinstitute.dsde.firecloud

import com.typesafe.config.ConfigFactory

object FireCloudConfig {
  private val config = ConfigFactory.load()

  object Auth {
    lazy val googleClientId = sys.env.get("GOOGLE_CLIENT_ID").get
    lazy val googleSecretJson = sys.env.get("GOOGLE_SECRET_JSON").get
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

    def entityPathFromWorkspace(namespace: String, name: String) = authUrl + entitiesPath.format(namespace, name)
    def importEntitiesPathFromWorkspace(namespace: String, name: String) = authUrl + importEntitiesPath.format(namespace, name)
  }

  object Thurloe {
    private val profile = config.getConfig("userprofile")
    lazy val baseUrl = sys.env.get("THURLOE_URL_ROOT").get
    lazy val authPrefix = profile.getString("authPrefix")
    lazy val authUrl = baseUrl + authPrefix
    lazy val setKey = profile.getString("setKey")
    lazy val getAll = profile.getString("getAll")
    lazy val get = profile.getString("get")
    lazy val delete = profile.getString("delete")
  }

  object FireCloud {
    lazy val baseUrl = sys.env.get("FIRECLOUD_URL_ROOT").get
  }

}
