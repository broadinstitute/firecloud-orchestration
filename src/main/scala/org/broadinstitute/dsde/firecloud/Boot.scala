package org.broadinstitute.dsde.firecloud

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import cats.effect.std.Queue
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.elastic.ElasticUtils
import org.broadinstitute.dsde.firecloud.model.{ExternalCredsMessage, ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.DisabledServiceFactory
import org.broadinstitute.dsde.workbench.google2.GoogleSubscriber
import org.broadinstitute.dsde.workbench.oauth2.{ClientId, OpenIDConnectConfiguration}
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor
import org.broadinstitute.dsde.workbench.util2.messaging.{CloudSubscriber, ReceivedMessage}
import org.elasticsearch.client.transport.TransportClient
import org.typelevel.log4cats.StructuredLogger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.reflect.ClassTag

object Boot extends IOApp with LazyLogging {

  private def startup(): IO[Unit] = {
    implicit val slogger: StructuredLogger[IO] = org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[IO]
    val processesResource = for {
      service <- fireCloudApiServiceResource()
      externalCredsSubscriber <- createExternalCredsSubscriber()
    } yield {
      implicit val system: ActorSystem = service.system
      List(
        // process for handling messages from the external creds service for RAS passport updates
        externalCredsSubscriber.messages.evalMap { msg =>
          service.nihServiceConstructor().processExternalCredsMessage(msg)
        },

        // start the subscriber
        Stream.eval(externalCredsSubscriber.start),

        // start the http server
        Stream.eval(
          IO.fromFuture(
            IO(
              Http()
                .newServerAt("0.0.0.0", 8080)
                .bindFlow(service.route)
                .recover { case t: Throwable =>
                  logger.error("FATAL - failure starting http server", t)
                }
            )
          )
        )
      )
    }

    // run all the processes concurrently
    processesResource
      .use { processes =>
        Stream
          .emits(processes)
          .covary[IO]
          .parJoin(processes.length)
          .handleErrorWith(error => Stream.emit(logger.error("FATAL - error starting Firecloud Orchestration", error)))
          .compile
          .drain
      }
  }

  private def fireCloudApiServiceResource(): Resource[IO, FireCloudApiService] =
    Resource.make {
      // we need an ActorSystem to host our application in
      implicit val system: ActorSystem = ActorSystem("FireCloud-Orchestration-API")

      val app: Application = buildApplication

      val agoraPermissionServiceConstructor: (UserInfo) => AgoraPermissionService =
        AgoraPermissionService.constructor(app)
      val exportEntitiesByTypeActorConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor =
        ExportEntitiesByTypeActor.constructor(app, system)
      val entityServiceConstructor: (ModelSchema) => EntityService = EntityService.constructor(app)
      val libraryServiceConstructor: (UserInfo) => LibraryService = LibraryService.constructor(app)
      val ontologyServiceConstructor: () => OntologyService = OntologyService.constructor(app)
      val namespaceServiceConstructor: (UserInfo) => NamespaceService = NamespaceService.constructor(app)
      val nihServiceConstructor: () => NihService = NihService.constructor(app)
      val registerServiceConstructor: () => RegisterService = RegisterService.constructor(app)
      val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app)
      val permissionReportServiceConstructor: (UserInfo) => PermissionReportService =
        PermissionReportService.constructor(app)
      val userServiceConstructor: (UserInfo) => UserService = UserService.constructor(app)
      val shareLogServiceConstructor: () => ShareLogService = ShareLogService.constructor(app)
      val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService =
        ManagedGroupService.constructor(app)

      // Boot HealthMonitor actor
      val healthChecks = new HealthChecks(app)
      val healthMonitorChecks = healthChecks.healthMonitorChecks
      val healthMonitor =
        system.actorOf(HealthMonitor.props(healthMonitorChecks().keySet)(healthMonitorChecks), "health-monitor")
      system.scheduler.scheduleWithFixedDelay(3.seconds, 1.minute, healthMonitor, HealthMonitor.CheckAll)

      val statusServiceConstructor: () => StatusService = StatusService.constructor(healthMonitor)

      for {
        oauth2Config <- OpenIDConnectConfiguration[IO](
          FireCloudConfig.Auth.authorityEndpoint,
          ClientId(FireCloudConfig.Auth.oidcClientId),
          extraAuthParams = Some("prompt=login"),
          authorityEndpointWithGoogleBillingScope = FireCloudConfig.Auth.authorityEndpointWithGoogleBillingScope
        )

        service <- IO {
          new FireCloudApiServiceImpl(
            agoraPermissionServiceConstructor,
            exportEntitiesByTypeActorConstructor,
            entityServiceConstructor,
            libraryServiceConstructor,
            ontologyServiceConstructor,
            namespaceServiceConstructor,
            nihServiceConstructor,
            registerServiceConstructor,
            workspaceServiceConstructor,
            statusServiceConstructor,
            permissionReportServiceConstructor,
            userServiceConstructor,
            shareLogServiceConstructor,
            managedGroupServiceConstructor,
            oauth2Config,
            app.samDAO
          )
        }
      } yield service
    } { service =>
      IO.fromFuture(IO(service.system.terminate())).void
    }

  private def buildApplication(implicit system: ActorSystem) = {
    // can't be disabled
    val rawlsDAO: RawlsDAO = new HttpRawlsDAO
    val samDAO: SamDAO = new HttpSamDAO
    val thurloeDAO: ThurloeDAO = new HttpThurloeDAO
    val ecmDAO: ExternalCredsDAO =
      if (FireCloudConfig.ExternalCreds.enabled) new HttpExternalCredsDAO else new DisabledExternalCredsDAO

    // can be disabled
    val agoraDAO: AgoraDAO =
      whenEnabled[AgoraDAO](FireCloudConfig.Agora.enabled, new HttpAgoraDAO(FireCloudConfig.Agora))
    val googleServicesDAO: GoogleServicesDAO = whenEnabled[GoogleServicesDAO](
      FireCloudConfig.GoogleCloud.enabled,
      new HttpGoogleServicesDAO()
    )
    val shibbolethDAO: ShibbolethDAO =
      whenEnabled[ShibbolethDAO](FireCloudConfig.Shibboleth.enabled, new HttpShibbolethDAO)
    val cwdsDAO: CwdsDAO = whenEnabled[CwdsDAO](
      FireCloudConfig.Cwds.enabled,
      new HttpCwdsDAO(FireCloudConfig.Cwds.enabled, FireCloudConfig.Cwds.supportedFormats)
    )

    val elasticSearchClient: Option[TransportClient] = Option.when(FireCloudConfig.ElasticSearch.enabled) {
      ElasticUtils.buildClient(FireCloudConfig.ElasticSearch.servers, FireCloudConfig.ElasticSearch.clusterName)
    }

    val ontologyDAO: OntologyDAO = elasticSearchClient
      .map(new ElasticSearchOntologyDAO(_, FireCloudConfig.ElasticSearch.ontologyIndexName))
      .getOrElse(DisabledServiceFactory.newDisabledService[OntologyDAO])
    val researchPurposeSupport: ResearchPurposeSupport = new ESResearchPurposeSupport(ontologyDAO)
    val searchDAO: SearchDAO = elasticSearchClient
      .map(new ElasticSearchDAO(_, FireCloudConfig.ElasticSearch.indexName, researchPurposeSupport))
      .getOrElse(DisabledServiceFactory.newDisabledService[SearchDAO])
    val shareLogDAO: ShareLogDAO = elasticSearchClient
      .map(new ElasticSearchShareLogDAO(_, FireCloudConfig.ElasticSearch.shareLogIndexName))
      .getOrElse(DisabledServiceFactory.newDisabledService[ShareLogDAO])

    Application(agoraDAO,
                googleServicesDAO,
                ontologyDAO,
                rawlsDAO,
                samDAO,
                searchDAO,
                researchPurposeSupport,
                thurloeDAO,
                shareLogDAO,
                shibbolethDAO,
                cwdsDAO,
                ecmDAO
    )
  }

  private def createExternalCredsSubscriber()(implicit
    logger: StructuredLogger[IO]
  ): Resource[IO, CloudSubscriber[IO, ExternalCredsMessage]] =
    if (FireCloudConfig.ExternalCreds.enabled) {
      import ExternalCredsMessage.externalCredsMessageDecoder
      for {
        queue <- Resource.eval(
          Queue.bounded[IO, ReceivedMessage[ExternalCredsMessage]](FireCloudConfig.ExternalCreds.subscriberQueueSize)
        )
        subscriber <- GoogleSubscriber.resource[IO, ExternalCredsMessage](
          FireCloudConfig.ExternalCreds.subscriberConfig,
          queue
        )
      } yield subscriber
    } else {
      logger.info("External Creds service is disabled, not subscribing to messages")
      Resource.pure[IO, CloudSubscriber[IO, ExternalCredsMessage]](new CloudSubscriber[IO, ExternalCredsMessage] {
        override def start: IO[Unit] = IO.unit
        override def stop: IO[Unit] = IO.unit
        override def messages: fs2.Stream[IO, ReceivedMessage[ExternalCredsMessage]] =
          fs2.Stream.never(IO.asyncForIO) // never prevents early termination
      })
    }

  private def whenEnabled[T: ClassTag](enabled: Boolean, realService: => T): T =
    if (enabled) {
      realService
    } else {
      DisabledServiceFactory.newDisabledService
    }

  override def run(args: List[String]): IO[ExitCode] = startup().as(ExitCode.Success)
}
