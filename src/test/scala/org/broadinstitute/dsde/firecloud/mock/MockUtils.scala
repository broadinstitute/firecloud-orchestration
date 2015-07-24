package org.broadinstitute.dsde.firecloud.mock

import java.text.SimpleDateFormat
import java.util.Date

import org.mockserver.model.{Cookie, Header}

object MockUtils {

   val isoDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")
   val cookie = new Cookie("iPlanetDirectoryPro", ".*")
   val header = new Header("Content-Type", "application/json")

   def randomPositiveInt(): Int = {
     scala.util.Random.nextInt(9) + 1
   }

   def randomAlpha(): String = {
     val chars = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
     randomStringFromCharList(randomPositiveInt(), chars)
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

 }
