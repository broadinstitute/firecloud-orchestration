package org.broadinstitute.dsde.firecloud

import com.typesafe.config.ConfigFactory
import spray.http.Uri.{Authority, Host}

object FireCloudConfig {
  private val config = ConfigFactory.load()

  object Auth {
    lazy val googleClientId = sys.env.get("GOOGLE_CLIENT_ID").get
    lazy val googleSecretJson = sys.env.get("GOOGLE_SECRET_JSON").get
    lazy val pemFile = sys.env.get("ORCHESTRATION_PEM").get
    lazy val pemFileClientId = sys.env.get("ORCHESTRATION_PEM_CLIENT_ID").get
    lazy val rawlsPemFile = sys.env.get("RAWLS_PEM").get
    lazy val rawlsPemFileClientId = sys.env.get("RAWLS_PEM_CLIENT_ID").get
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
    lazy val entityQueryPath = workspace.getString("entityQueryPath")
    lazy val importEntitiesPath = workspace.getString("importEntitiesPath")
    lazy val workspacesEntitiesCopyPath = workspace.getString("workspacesEntitiesCopyPath")
    lazy val workspacesEntitiesCopyUrl = authUrl + workspacesEntitiesCopyPath
    lazy val submissionsPath = workspace.getString("submissionsPath")
    lazy val submissionsUrl = authUrl + submissionsPath
    lazy val submissionsIdPath = workspace.getString("submissionsIdPath")
    lazy val overwriteGroupMembershipPath = workspace.getString("overwriteGroupMembershipPath")
    lazy val submissionQueueStatusPath = workspace.getString("submissionQueueStatusPath")
    lazy val submissionQueueStatusUrl = authUrl + submissionQueueStatusPath

    def entityPathFromWorkspace(namespace: String, name: String) = authUrl + entitiesPath.format(namespace, name)
    def entityQueryPathFromWorkspace(namespace: String, name: String) = authUrl + entityQueryPath.format(namespace, name)
    def importEntitiesPathFromWorkspace(namespace: String, name: String) = authUrl + importEntitiesPath.format(namespace, name)
    def overwriteGroupMembershipUrlFromGroupName(groupName: String) = authUrl + overwriteGroupMembershipPath.format(groupName)
    def workspacesPath(namespace: String, name: String) = authUrl + workspacesPath.format(namespace, name)
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
    lazy val postNotify = profile.getString("postNotify")
  }

  object FireCloud {
    lazy val baseUrl = sys.env.get("FIRECLOUD_URL_ROOT").get
    lazy val fireCloudId = sys.env.get("FIRE_CLOUD_ID").get
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

  object Notification {
    //this config entry is only present during testing, otherwise it's set via environment variables in docker-compose
    //we must lazily evaluate this setting so we don't get "configuration not found" errors
    lazy private val notification = config.getConfig("notification")
    lazy val activationTemplateId = sys.env.get("ACTIVATION_TEMPLATE_ID").getOrElse(notification.getString("activationTemplateId"))
  }

  object ElasticSearch {
    lazy val servers: Seq[Authority] = (sys.env.get("ELASTICSEARCH_URLS").get.split(',') map { hostport =>
      val hp = hostport.split(':')
      Authority(Host(hp(0)), hp(1).toInt)
    })
    lazy val indexName = sys.env.get("ELASTICSEARCH_INDEX").get
  }

}
