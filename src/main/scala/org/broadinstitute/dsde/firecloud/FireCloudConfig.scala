package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.{Authority, Host, Query}
import com.typesafe.config.{Config, ConfigFactory, ConfigObject}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectiveUtils, NihWhitelist}
import org.broadinstitute.dsde.rawls.model.{EntityQuery, SortDirections}
import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName

import scala.jdk.CollectionConverters._
import scala.util.Try

object FireCloudConfig {
  private val config = ConfigFactory.load()

  object Auth {
    private val auth = config.getConfig("auth")
    // OIDC configuration using PKCE flow
    val authorityEndpoint = auth.getString("authorityEndpoint")
    val oidcClientId = auth.getString("oidcClientId")
    val authorityEndpointWithGoogleBillingScope = auth.optionalString("authorityEndpointWithGoogleBillingScope")

    // credentials for orchestration's "firecloud" service account, used for admin duties
    // lazy - only required when google is enabled
    lazy val firecloudAdminSAJsonFile = auth.getString("firecloudAdminSA")
    // credentials for the rawls service account, used for writing files to buckets for import
    // lazy - only required when import service is enabled
    lazy val rawlsSAJsonFile = auth.getString("rawlsSA")
  }

  object Agora {
    // lazy - only required when agora is enabled
    private lazy val methods = config.getConfig("methods")
    private lazy val agora = config.getConfig("agora")
    lazy val baseUrl = agora.getString("baseUrl")
    lazy val authPrefix = methods.getString("authPrefix")
    lazy val authUrl = baseUrl + authPrefix
    val enabled = agora.optionalBoolean("enabled").getOrElse(true)
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
    val workspacesEntitiesCopyPath = workspace.getString("workspacesEntitiesCopyPath")
    def workspacesEntitiesCopyUrl(linkExistingEntities: Boolean) = authUrl + workspacesEntitiesCopyPath + "?linkExistingEntities=%s".format(linkExistingEntities)
    val submissionsCountPath = workspace.getString("submissionsCountPath")
    val submissionsPath = workspace.getString("submissionsPath")
    val submissionsIdPath = workspace.getString("submissionsIdPath")
    val submissionsWorkflowIdPath = workspace.getString("submissionsWorkflowIdPath")
    val submissionsWorkflowIdOutputsPath = workspace.getString("submissionsWorkflowIdOutputsPath")
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
          baseEntityQueryUri.withQuery(Query(filteredQMap))
        case _ => baseEntityQueryUri
      }
    }
  }

  object Sam {
    private val sam = config.getConfig("sam")
    val baseUrl = sam.getString("baseUrl")
  }

  object CromIAM {
    // lazy - only required when cromiam is enabled
    private lazy val cromIam = config.getConfig("cromiam")
    lazy val baseUrl = cromIam.getString("baseUrl")
    lazy val authPrefix = cromIam.getString("authPrefix")
    lazy val authUrl = baseUrl + authPrefix
    val enabled = cromIam.optionalBoolean("enabled").getOrElse(true)
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
    val validPreferenceKeyPrefixes = profile.getStringList("validPreferenceKeyPrefixes").asScala.toSet
    val validPreferenceKeys = profile.getStringList("validPreferenceKeys").asScala.toSet
  }

  object Cwds {
    // lazy - only required when cwds is enabled
    private lazy val cwds = config.getConfig("cwds")
    lazy val baseUrl: String = cwds.getString("baseUrl")
    lazy val enabled: Boolean = cwds.getBoolean("enabled")
    lazy val supportedFormats: List[String] = cwds.getStringList("supportedFormats").asScala.toList
    lazy val bucket: String = cwds.getString("bucketName")
  }

  object FireCloud {
    private val firecloud = config.getConfig("firecloud")
    val fireCloudId = firecloud.getString("fireCloudId")

    // lazy - only required when google is enabled
    lazy val serviceProject = firecloud.getString("serviceProject")
    lazy val supportDomain = firecloud.getString("supportDomain")
    lazy val supportPrefix = firecloud.getString("supportPrefix")
    lazy val userAdminAccount = firecloud.getString("userAdminAccount")
  }

  object Shibboleth {
    // lazy - only required when shibboleth is enabled
    private lazy val shibboleth = config.getConfig("shibboleth")
    lazy val publicKeyUrl = shibboleth.getString("publicKeyUrl")
    val enabled = shibboleth.optionalBoolean("enabled").getOrElse(true)
  }

  object Nih {
    // lazy - only required when nih is enabled
    private lazy val nih = config.getConfig("nih")
    lazy val whitelistBucket = nih.getString("whitelistBucket")
    lazy val whitelists: Set[NihWhitelist] = {
      val whitelistConfigs = nih.getConfig("whitelists")

      whitelistConfigs.root.asScala.collect { case (name, configObject:ConfigObject) =>
        val config = configObject.toConfig
        val rawlsGroup = config.getString("rawlsGroup")
        val fileName = config.getString("fileName")

        NihWhitelist(name, WorkbenchGroupName(rawlsGroup), fileName)
      }
    }.toSet
    val enabled = nih.optionalBoolean("enabled").getOrElse(true)
  }

  object ElasticSearch {
    // lazy - only required when elasticsearch is enabled
    private lazy val elasticsearch = config.getConfig("elasticsearch")
    lazy val servers: Seq[Authority] = parseESServers(elasticsearch.getString("urls"))
    lazy val clusterName = elasticsearch.getString("clusterName")
    lazy val indexName = elasticsearch.getString("index") // for library
    lazy val ontologyIndexName = elasticsearch.getString("ontologyIndex")
    lazy val discoverGroupNames = elasticsearch.getStringList("discoverGroupNames")
    lazy val shareLogIndexName: String = elasticsearch.getString("shareLogIndex")
    lazy val maxAggregations: Int = Try(elasticsearch.getInt("maxAggregations")).getOrElse(1000)
    val enabled = elasticsearch.optionalBoolean("enabled").getOrElse(true)
  }

  def parseESServers(confString: String): Seq[Authority] = {
    confString.split(',').toIndexedSeq map { hostport =>
      val hp = hostport.split(':')
      Authority(Host(hp(0)), hp(1).toInt)
    }
  }

  object GoogleCloud {
    // lazy - only required when google is enabled
    private lazy val googlecloud = config.getConfig("googlecloud")
    lazy val priceListUrl = googlecloud.getString("priceListUrl")
    lazy val priceListEgressKey = googlecloud.getString("priceListEgressKey")
    lazy val priceListStorageKey = googlecloud.getString("priceListStorageKey")
    lazy val defaultStoragePriceListConf = googlecloud.getConfig("defaultStoragePriceList")
    lazy val defaultStoragePriceList = defaultStoragePriceListConf.root().keySet().asScala.map(key => key -> BigDecimal(defaultStoragePriceListConf.getDouble(key))).toMap
    lazy val defaultEgressPriceListConf = googlecloud.getConfig("defaultEgressPriceList")
    lazy val defaultEgressPriceList = defaultEgressPriceListConf.root().keySet().asScala.map(key => key.toLong -> BigDecimal(defaultEgressPriceListConf.getDouble(key))).toMap
    val enabled = googlecloud.optionalBoolean("enabled").getOrElse(true)
  }

  object Duos {
    // lazy - only required when duos is enabled
    private lazy val duos = config.getConfig("duos")
    lazy val baseOntologyUrl = duos.getString("baseOntologyUrl")
    lazy val dulvn = duos.getInt("dulvn")
    val enabled = duos.optionalBoolean("enabled").getOrElse(true)
  }

  object Notification {
    // lazy - only required when notification is enabled
    private lazy val notification = config.getConfig("notification")
    lazy val fullyQualifiedNotificationTopic: String = notification.getString("fullyQualifiedNotificationTopic")
    val enabled = notification.optionalBoolean("enabled").getOrElse(true)
  }

  object StaticNotebooks {
    private val staticNotebooks = config.getConfig("staticNotebooks")
    val baseUrl: String = staticNotebooks.getString("baseUrl")
  }

  object ImportService {
    // lazy - only required when import service is enabled
    lazy val server: String = config.getString("importService.server")
    lazy val bucket: String = config.getString("importService.bucketName")
    val enabled: Boolean = config.optionalBoolean("importService.enabled").getOrElse(true)
  }

  implicit class RichConfig(val config: Config) {
    private def getOptional[T](path: String, get: String => T): Option[T] = {
      if (config.hasPath(path)) {
        Some(get(path))
      } else {
        None
      }
    }
    def optionalString(path: String): Option[String] = getOptional(path, config.getString)
    def optionalInt(path: String): Option[Int] = getOptional(path, config.getInt)
    def optionalDouble(path: String): Option[Double] = getOptional(path, config.getDouble)
    def optionalBoolean(path: String): Option[Boolean] = getOptional(path, config.getBoolean)
  }

}
