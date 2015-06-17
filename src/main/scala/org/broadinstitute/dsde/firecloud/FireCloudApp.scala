package org.broadinstitute.dsde.firecloud

import akka.actor.{ActorSystem, Props}
import org.broadinstitute.dsde.vault.common.util.ServerInitializer
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object FireCloudApp {

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("FireCloud-Orchestration-API")
  val timeoutDuration = FiniteDuration(FireCloudConfig.HttpConfig.timeoutSeconds, SECONDS)

  lazy val log = LoggerFactory.getLogger(getClass)

  // create and start our service actor
  val service = system.actorOf(Props[FireCloudServiceActor], "FireCloudService")

  def main(args: Array[String]) {
    log.info("FireCloud Orchestration instance starting.")
    ServerInitializer.startWebServiceActors(Props[FireCloudServiceActor], FireCloudConfig.HttpConfig.interface, FireCloudConfig.HttpConfig.port, timeoutDuration, system)
  }

}
