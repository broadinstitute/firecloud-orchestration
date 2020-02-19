package org.broadinstitute.dsde.firecloud

import scala.collection.JavaConverters._
import com.typesafe.config.{ConfigFactory, ConfigObject}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectiveUtils, NihWhitelist}
import org.broadinstitute.dsde.rawls.model.{EntityQuery, SortDirections}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import spray.http.Uri
import spray.http.Uri.{Authority, Host}

import scala.util.Try

object FireCloudConfig {
  private val config = ConfigFactory.load()

  object Auth {
    private val auth = config.getConfig("auth")
    val googleClientId = auth.getString("googleClientId")
    val googleSecretJson = auth.getString("googleSecretsJson")

    // credentials for orchestration's "firecloud" service account, used for admin duties
    val firecloudAdminSAJsonFile = auth.getString("firecloudAdminSA")

    // credentials for the rawls service account, used for signing GCS urls
    val rawlsSAJsonFile = auth.getString("rawlsSA")

    // credentials for the trial billing service account, used for free trial duties
    val trialBillingSAJsonFile = auth.getString("trialBillingSA")

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
    val entityBagitMaximumSize = workspace.getInt("entityBagitMaximumSize")
    val importEntitiesPath = workspace.getString("importEntitiesPath")
    val workspacesEntitiesCopyPath = workspace.getString("workspacesEntitiesCopyPath")
    def workspacesEntitiesCopyUrl(linkExistingEntities: Boolean) = authUrl + workspacesEntitiesCopyPath + "?linkExistingEntities=%s".format(linkExistingEntities)
    val submissionsCountPath = workspace.getString("submissionsCountPath")
    val submissionsPath = workspace.getString("submissionsPath")
    val submissionsUrl = authUrl + submissionsPath
    val submissionsIdPath = workspace.getString("submissionsIdPath")
    val submissionsWorkflowIdPath = workspace.getString("submissionsWorkflowIdPath")
    val submissionsWorkflowIdOutputsPath = workspace.getString("submissionsWorkflowIdOutputsPath")
    val overwriteGroupMembershipPath = workspace.getString("overwriteGroupMembershipPath")
    val alterGroupMembershipPath = workspace.getString("alterGroupMembershipPath")
    val createGroupPath = workspace.getString("createGroup")
    val submissionQueueStatusPath = workspace.getString("submissionQueueStatusPath")
    val submissionQueueStatusUrl = authUrl + submissionQueueStatusPath
    val executionEngineVersionPath = workspace.getString("executionEngineVersionPath")
    val executionEngineVersionUrl = baseUrl + executionEngineVersionPath
    val notificationsPath = workspace.getString("notificationsPath")
    val notificationsUrl = authUrl + notificationsPath
    val defaultPageSize = rawls.getInt("defaultPageSize")

    def entityPathFromWorkspace(namespace: String, name: String) = authUrl + entitiesPath.format(namespace, name)
    def entityQueryPathFromWorkspace(namespace: String, name: String) = authUrl + entityQueryPath.format(namespace, name)
    def importEntitiesPathFromWorkspace(namespace: String, name: String) = authUrl + importEntitiesPath.format(namespace, name)
    def overwriteGroupMembershipUrlFromGroupName(groupName: String, role: String) = authUrl + overwriteGroupMembershipPath.format(groupName, role)
    def alterGroupMembershipUrlFromGroupName(groupName: String, role: String, email: String) = authUrl + alterGroupMembershipPath.format(groupName, role, email)
    def createGroup(groupName: String) = authUrl + createGroupPath.format(groupName)
    def entityQueryUriFromWorkspaceAndQuery(workspaceNamespace: String, workspaceName: String, entityType: String, query: Option[EntityQuery] = None): Uri = {
      val baseEntityQueryUri = Uri(FireCloudDirectiveUtils.encodeUri(s"${entityQueryPathFromWorkspace(workspaceNamespace, workspaceName)}/$entityType"))
      query match {
        case Some(q) =>
          val qMap: Map[String, String] = Map(
            ("page", q.page.toString),
            ("pageSize", q.pageSize.toString),
            ("sortField", q.sortField),
            ("sortDirection", SortDirections.toString(q.sortDirection)))
          val filteredQMap = q.filterTerms match {
            case Some(f) => qMap + ("filterTerms" -> f)
            case _ => qMap
          }
          baseEntityQueryUri.withQuery(filteredQMap)
        case _ => baseEntityQueryUri
      }
    }
  }

  object Sam {
    private val sam = config.getConfig("sam")
    val baseUrl = sam.getString("baseUrl")
  }

  object CromIAM {
    private val cromIam = config.getConfig("cromiam")
    val baseUrl = cromIam.getString("baseUrl")
    val authPrefix = cromIam.getString("authPrefix")
    val authUrl = baseUrl + authPrefix
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
  }

  object FireCloud {
    private val firecloud = config.getConfig("firecloud")
    val baseUrl = firecloud.getString("baseUrl")
    val fireCloudId = firecloud.getString("fireCloudId")
    val fireCloudPortalUrl = firecloud.getString("portalUrl")
    val serviceProject = firecloud.getString("serviceProject")
    val supportDomain = firecloud.getString("supportDomain")
    val supportPrefix = firecloud.getString("supportPrefix")
    val userAdminAccount = firecloud.getString("userAdminAccount")
  }

  object Shibboleth {
    private val shibboleth = config.getConfig("shibboleth")
    val signingKey = shibboleth.getString("jwtSigningKey")
  }

  object Nih {
    private val nih = config.getConfig("nih")
    val whitelistBucket = nih.getString("whitelistBucket")
    val whitelists: Set[NihWhitelist] = {
      val whitelistConfigs = nih.getConfig("whitelists")

      whitelistConfigs.root.asScala.map { case (name, configObject: ConfigObject) =>
        val config = configObject.toConfig
        val rawlsGroup = config.getString("rawlsGroup")
        val fileName = config.getString("fileName")

        NihWhitelist(name, WorkbenchGroupName(rawlsGroup), fileName)
      }
    }.toSet
  }

  object ElasticSearch {
    private val elasticsearch = config.getConfig("elasticsearch")
    val servers: Seq[Authority] = parseESServers(elasticsearch.getString("urls"))
    val clusterName = elasticsearch.getString("clusterName")
    val indexName = elasticsearch.getString("index") // for library
    val ontologyIndexName = elasticsearch.getString("ontologyIndex")
    val trialIndexName = elasticsearch.getString("trialIndex")
    val discoverGroupNames = elasticsearch.getStringList("discoverGroupNames")
    val shareLogIndexName: String = elasticsearch.getString("shareLogIndex")
    val maxAggregations: Int = Try(elasticsearch.getInt("maxAggregations")).getOrElse(1000)
  }

  def parseESServers(confString: String): Seq[Authority] = {
    confString.split(',') map { hostport =>
      val hp = hostport.split(':')
      Authority(Host(hp(0)), hp(1).toInt)
    }
  }

  object GoogleCloud {
    private val googlecloud = config.getConfig("googlecloud")
    val priceListUrl = googlecloud.getString("priceListUrl")
    val priceListEgressKey = googlecloud.getString("priceListEgressKey")
    val priceListStorageKey = googlecloud.getString("priceListStorageKey")
  }

  object Duos {
    private val duos = config.getConfig("duos")
    val baseConsentUrl = duos.getString("baseConsentUrl")
    val baseOntologyUrl = duos.getString("baseOntologyUrl")
    val dulvn = duos.getInt("dulvn")
  }

  object Trial {
    private val trial = config.getConfig("trial")
    val durationDays = trial.getInt("durationDays")
    val managerGroup = trial.getString("managerGroup")
    val billingAccount = trial.getString("billingAccount")
    val projectBufferSize = trial.getInt("projectBufferSize")
    val spreadsheet = trial.getConfig("spreadsheet")
    val spreadsheetId = spreadsheet.getString("id")
    val spreadsheetUpdateFrequencyMinutes = spreadsheet.getInt("updateFrequencyMinutes")
  }

  object Metrics {
    private val metrics = config.getConfig("metrics")
    val logitFrequencyMinutes = metrics.getInt("logitFrequencyMinutes")
    val logitUrl: String = metrics.getString("logitUrl")
    val logitApiKey: Option[String] = if (metrics.hasPath("logitApiKey")) Some(metrics.getString("logitApiKey")) else None
    val entityWorkspaceNamespace: Option[String] = if (metrics.hasPath("entityWorkspaceNamespace")) Some(metrics.getString("entityWorkspaceNamespace")) else None
    val entityWorkspaceName: Option[String] = if (metrics.hasPath("entityWorkspaceName")) Some(metrics.getString("entityWorkspaceName")) else None
    val libraryNamespaces: List[String] = metrics.getStringList("libraryWorkspaceNamespace").asScala.toList
  }

  object Spray {
    private val spray = config.getConfig("spray")
    // grab a copy of this Spray setting to use when displaying an error message
    lazy val chunkLimit = spray.getString("can.client.response-chunk-aggregation-limit")
  }

  object Notification {
    private val notification = config.getConfig("notification")
    val fullyQualifiedNotificationTopic: String = notification.getString("fullyQualifiedNotificationTopic")
  }

  object StaticNotebooks {
    private val staticNotebooks = config.getConfig("staticNotebooks")
    val baseUrl: String = staticNotebooks.getString("baseUrl")
  }

  object Arrow {
    private val arrow = config.getConfig("arrow")
    val appName: String = if (arrow.hasPath("appName")) arrow.getString("appName") else "avro-import"
    val baseUrl: String = arrow.getString("baseUrl")
    val bucketName: String = arrow.getString("bucketName")
  }
}
