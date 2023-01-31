package org.broadinstitute.dsde.test.api.orch

import java.util.{Calendar, UUID}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.test.OrchConfig
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig}
import org.broadinstitute.dsde.workbench.dao.Google.googleStorageDAO
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.GcsEntityTypes.{Group, User}
import org.broadinstitute.dsde.workbench.model.google.GcsRoles.Reader
import org.broadinstitute.dsde.workbench.model.google.{EmailGcsEntity, GcsBucketName, GcsObjectName, GcsPath}
import org.broadinstitute.dsde.workbench.service.Sam
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Seconds, Span}

import scala.util.{Failure, Success, Try}

trait StorageApiSpecSupport extends ScalaFutures with LazyLogging {

  implicit val storagePatience: PatienceConfig = PatienceConfig(timeout = scaled(Span(4, Minutes)), interval = scaled(Span(2, Seconds)))

  // these are hardcoded and should never change. They refer to a static pre-created bucket in broad-dsde-qa.
  final val fixtureBucket: GcsBucketName = GcsBucketName("fixtures-for-tests")
  final val fixtureDir: String = "fixtures"

  final val smallFileName: String = "small-text-file.txt"
  final val largeFileName: String = "ninemegabytes.file"
  final val imageFileName: String = "broad_logo.png"

  final val smallFileFixture: GcsPath = GcsPath(fixtureBucket, GcsObjectName(s"$fixtureDir/$smallFileName"))
  final val largeFileFixture: GcsPath = GcsPath(fixtureBucket, GcsObjectName(s"$fixtureDir/$largeFileName"))
  final val imageFileFixture: GcsPath = GcsPath(fixtureBucket, GcsObjectName(s"$fixtureDir/$imageFileName"))

  // temporary subdirectory to hold files for a single run of tests.
  lazy val testDir: String = {
    val tag = "apitest"
    val time = Calendar.getInstance.getTime.toInstant.getEpochSecond
    val username = Try(System.getProperty("user.name")) match {
      case Success(str) => str.toLowerCase
      case Failure(_) => "unknownuser"
    }
    val hostname = Try(java.net.InetAddress.getLocalHost.getHostName) match {
      case Success(str) => str.toLowerCase
      case Failure(_) => "unknownhostname"
    }
    Seq(tag, username, hostname, time).mkString("_")
  }

  private def withFile(srcPath: GcsPath, destPath: GcsPath, testCode: GcsPath => Any): Unit = {
    logger.debug(s"copying $srcPath to $destPath ...")
    googleStorageDAO.copyObject(srcPath.bucketName, srcPath.objectName, destPath.bucketName, destPath.objectName).futureValue
    try {
      testCode(destPath)
    } finally {
      logger.debug(s"cleaning up $destPath ...")
       googleStorageDAO.removeObject(destPath.bucketName, destPath.objectName)
    }
  }

  def withSmallFile(testCode: GcsPath => Any): Unit = {
    val uuid = UUID.randomUUID().toString
    val srcPath = smallFileFixture
    val destPath = GcsPath(fixtureBucket, GcsObjectName(s"$testDir/$uuid/$smallFileName"))
    withFile(srcPath, destPath, testCode)
  }

  def withLargeFile(testCode: GcsPath => Any): Unit = {
    val uuid = UUID.randomUUID().toString
    val srcPath = largeFileFixture
    val destPath = GcsPath(fixtureBucket, GcsObjectName(s"$testDir/$uuid/$largeFileName"))
    withFile(srcPath, destPath, testCode)
  }

  def setStudentOnly(path: GcsPath, student: Credentials)(implicit token: AuthToken): Unit = {
    setStudentOnlyNoWait(path, student)
    Thread.sleep(ServiceTestConfig.FireCloud.waitForAccessTime.toMillis)
  }

  def setStudentOnlyNoWait(path: GcsPath, student: Credentials)(implicit token: AuthToken): Unit = {
    // give student's proxy group access to this file
    val proxyGroup = Sam.user.proxyGroup(student.email)
    val proxyGroupEntity = EmailGcsEntity(Group, proxyGroup)
    googleStorageDAO.setObjectAccessControl(path.bucketName, path.objectName, proxyGroupEntity, Reader).futureValue
  }

  def setStudentAndSA(path: GcsPath, student: Credentials)(implicit token: AuthToken): Unit = {
    // give student's proxy group access to this file
    setStudentOnlyNoWait(path, student)
    // give signing SA access to this file
    val signingSAEntity = EmailGcsEntity(User, WorkbenchEmail(OrchConfig.GCS.orchStorageSigningSA))
    googleStorageDAO.setObjectAccessControl(path.bucketName, path.objectName, signingSAEntity, Reader).futureValue
    Thread.sleep(ServiceTestConfig.FireCloud.waitForAccessTime.toMillis)
  }

}
