package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.Profile
import org.broadinstitute.dsde.firecloud.core.ProfileClient
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.scalatest.FreeSpec

class ProfileClientSpec extends FreeSpec {


  def makeProfile(lastLinkTime: Option[Long] = None, linkExpireTime: Option[Long] = None): Profile = {
    Profile (
      "firstName",
      "lastName",
      "title",
      Option.empty,
      "institute",
      "institutionalProgram",
      "programLocationCity",
      "programLocationState",
      "programLocationCountry",
      "pi",
      "nonProfitStatus",
      Some("linkedNihUsername"),
      lastLinkTime, //lastLinkTime
      linkExpireTime, //linkExpireTime
      Some(false) //isDbGapAuthorized, unused
    )
  }

  def assertExpireTimeWasUpdated(calculatedExpire: Long) = {
    // we expect the calculated time to be now + 24 hours. We can't test this exactly because milliseconds
    // may have elapsed in processing, so we rely on DateUtils rounding, and give a
    val diff = DateUtils.hoursUntil(calculatedExpire)

    assert( diff == 23 || diff == 24,
      "Expected expiration 24 hours in the future, found expiration " + diff + " hours away" )
  }


  "ExpireTimeCalculationTests" - {


    // following tests: lastLink MORE than 24 hours in the future
    "lastLink > 24 hours in the past and expire > 24 hours in the future" - {
      "should return expire as now+24hours" in {
        val lastLink = DateUtils.nowMinus30Days
        val expire = DateUtils.nowPlus30Days

        val profile = makeProfile(Some(lastLink), Some(expire))
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)

        assertExpireTimeWasUpdated(calculatedExpire)
      }
    }

    "lastLink > 24 hours in the past and expire < 24 hours in the future" - {
      "should return expire time unchanged" in {
        val lastLink = DateUtils.nowMinus30Days
        val expire = DateUtils.nowPlus1Hour

        val profile = makeProfile(Some(lastLink), Some(expire))
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)
        assertResult(expire) { calculatedExpire }
      }
    }

    "lastLink > 24 hours in the past and expire > 24 hours in the past" - {
      "should return expire time unchanged" in {
        val lastLink = DateUtils.nowMinus30Days
        val expire = DateUtils.nowMinus30Days

        val profile = makeProfile(Some(lastLink), Some(expire))
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)
        assertResult(expire) { calculatedExpire }
      }
    }

    "lastLink > 24 hours in the past and expire < 24 hours in the past" - {
      "should return expire time unchanged" in {
        val lastLink = DateUtils.nowMinus30Days
        val expire = DateUtils.nowMinus1Hour

        val profile = makeProfile(Some(lastLink), Some(expire))
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)
        assertResult(expire) { calculatedExpire }
      }
    }


    // following tests: lastLink LESS than 24 hours in the future
    "lastLink < 24 hours in the past and expire > 24 hours in the future" - {
      "should return expire time unchanged" in {
        val lastLink = DateUtils.nowMinus1Hour
        val expire = DateUtils.nowPlus30Days

        val profile = makeProfile(Some(lastLink), Some(expire))
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)
        assertResult(expire) { calculatedExpire }
      }
    }

    "lastLink < 24 hours in the past and expire < 24 hours in the future" - {
      "should return expire time unchanged" in {
        val lastLink = DateUtils.nowMinus1Hour
        val expire = DateUtils.nowPlus1Hour

        val profile = makeProfile(Some(lastLink), Some(expire))
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)
        assertResult(expire) { calculatedExpire }
      }
    }

    "lastLink < 24 hours in the past and expire > 24 hours in the past" - {
      "should return expire time unchanged" in {
        val lastLink = DateUtils.nowMinus1Hour
        val expire = DateUtils.nowMinus30Days

        val profile = makeProfile(Some(lastLink), Some(expire))
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)
        assertResult(expire) { calculatedExpire }
      }
    }

    "lastLink < 24 hours in the past and expire < 24 hours in the past" - {
      "should return expire time unchanged" in {
        val lastLink = DateUtils.nowMinus1Hour
        val expire = DateUtils.nowMinus1Hour

        val profile = makeProfile(Some(lastLink), Some(expire))
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)
        assertResult(expire) { calculatedExpire }
      }
    }

    "No lastLink or expire time" - {
      "should return expire as now+24hours" in {
        val profile = makeProfile()
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)

        assertExpireTimeWasUpdated(calculatedExpire)
      }
    }

    "No lastLink time" - {
      "should return expire as now+24hours" in {
        val expire = DateUtils.nowPlus1Hour
        val profile = makeProfile(None, Some(expire))
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)

        assertExpireTimeWasUpdated(calculatedExpire)
      }
    }

    "No expire time" - {
      "should return expire as now+24hours" in {
        val lastLink = DateUtils.nowMinus1Hour
        val profile = makeProfile(Some(lastLink), None)
        val calculatedExpire = ProfileClient.calculateExpireTime(profile)

        assertExpireTimeWasUpdated(calculatedExpire)
      }
    }




  }

}
