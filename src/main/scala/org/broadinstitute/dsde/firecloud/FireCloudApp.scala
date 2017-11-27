package org.broadinstitute.dsde.firecloud

import scala.concurrent.duration._
import akka.actor.{ActorSystem, Props}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.vault.common.util.ServerInitializer

object FireCloudApp extends LazyLogging {

  // we need an ActorSystem to host our application in
  val system = ActorSystem("FireCloud-Orchestration-API")
  val timeoutDuration = FiniteDuration(FireCloudConfig.HttpConfig.timeoutSeconds, SECONDS)

  def main(args: Array[String]) {
    logger.info("FireCloud Orchestration instance starting.")
    ServerInitializer.startWebServiceActors(
      Props[FireCloudServiceActor],
      FireCloudConfig.HttpConfig.interface,
      FireCloudConfig.HttpConfig.port,
      timeoutDuration,
      system
    )
  }

}
