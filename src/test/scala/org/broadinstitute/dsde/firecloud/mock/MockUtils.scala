package org.broadinstitute.dsde.firecloud.mock

import java.text.SimpleDateFormat
import java.util.Date

import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.mockserver.model.Header
import akka.http.scaladsl.model.StatusCode

object MockUtils {

  val isoDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")
  val authHeader = new Header("Authorization", "Bearer mF_9.B5f-4.1JqM")
  val header = new Header("Content-Type", "application/json")
  val workspaceServerPort = 8990
  val methodsServerPort = 8989
  val thurloeServerPort = 8991
  val consentServerPort = 8992
  val ontologyServerPort = 8993
  val samServerPort = 8994
  val cromiamServerPort = 8995
  val importServiceServerPort = 9394

   def randomPositiveInt(): Int = {
     scala.util.Random.nextInt(9) + 1
   }

   def randomAlpha(): String = {
     val chars = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
     randomStringFromCharList(randomPositiveInt(), chars)
   }

  def randomBoolean(): Boolean = {
    scala.util.Random.nextBoolean()
  }

   def randomStringFromCharList(length: Int, chars: Seq[Char]): String = {
     val sb = new StringBuilder
     for (i <- 1 to length) {
       val randomNum = util.Random.nextInt(chars.length)
       sb.append(chars(randomNum))
     }
     sb.toString()
   }

   def isoDate(): String = {
     isoDateFormat.format(new Date())
   }

  def rawlsErrorReport(statusCode: StatusCode) =
    ErrorReport("Rawls", "dummy text", Option(statusCode), Seq(), Seq(), None)

  def randomElement[A](list: List[A]): A = {
    list(scala.util.Random.nextInt(list.length))
  }

}
