/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.specs

import java.util.concurrent.{Executors, ThreadFactory, TimeoutException}

import com.waz.ZLog.{LogTag, error, verbose}
import com.waz.log.{InternalLog, SystemLogOutput}
import com.waz.service.ZMessaging
import com.waz.testutils.TestClock
import com.waz.threading.{SerialDispatchQueue, Threading}
import com.waz.utils._
import com.waz.utils.wrappers.{Intent, JVMIntentUtil, JavaURIUtil, URI, _}
import com.waz.{HockeyApp, HockeyAppUtil, ZLog}
import org.scalamock.scalatest.MockFactory
import org.scalatest._
import org.threeten.bp.Instant

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

//TODO somehow check other threads for failures that might normally be swallowed up and fail tests accordingly
abstract class AndroidFreeSpec extends FeatureSpec with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with MockFactory { this: Suite =>

  val defaultTimeout = 5.seconds

  val clock = TestClock()

  override protected def beforeEach() = {
    super.beforeEach()
    clock.reset()
  }

  //Ensures that Android wrappers are assigned with a non-Android implementation so that tests can run on the JVM
  override protected def beforeAll() = {
    URI.setUtil(JavaURIUtil)

    DB.setUtil(new DBUtil {
      override def ContentValues(): DBContentValues = DBContentValuesMap()
    })

    isTest = true

    ZMessaging.clock = clock

//    InternalLog.reset()
//    InternalLog.add(new SystemLogOutput)

    Intent.setUtil(JVMIntentUtil)

    Threading.setUi(new SerialDispatchQueue({
      Threading.executionContext(Executors.newSingleThreadExecutor(new ThreadFactory {
        override def newThread(r: Runnable) = {
          new Thread(r, Threading.testUiThreadName)
        }
      }))
    }, Threading.testUiThreadName))

    Localytics.setUtil(None)

    HockeyApp.setUtil(Some(new HockeyAppUtil {
      override def saveException(t: Throwable, description: String)(implicit tag: LogTag) = {
        error(s"Logged to HockeyApp: $description", t)
      }
    }))
  }

  /**
    * Here we wait for all threads to finish their current tasks as to allow each test to run with a clean threading profile.
    * If there are still tasks pending, we fail, as it likely means an error somewhere.
    */
  override def withFixture(test: NoArgTest) = super.withFixture(test) match {
    case Succeeded =>
      if (!tasksCompletedAfterWait) {
        Failed(new TimeoutException(s"Background tasks continued running after test for ${defaultTimeout.toSeconds} seconds: Potential threading issue!"))
      } else Succeeded
    case outcome => outcome
  }

  def result[A](future: Future[A])(implicit defaultDuration: FiniteDuration = 5.seconds): A = Await.result(future, defaultDuration)

  /**
    * Very useful for checking that something DOESN'T happen (e.g., ensure that a signal doesn't get updated after
    * performing a series of actions)
    */
  def awaitAllTasks(implicit timeout: FiniteDuration = defaultTimeout) = {
    if (!tasksCompletedAfterWait) fail(new TimeoutException(s"Background tasks didn't complete in ${timeout.toSeconds} seconds"))
  }

  private def tasksCompletedAfterWait(implicit timeout: FiniteDuration = defaultTimeout) = {
    val start = Instant.now
    import Threading._
    def tasksRemaining = Seq(IO, ImageDispatcher, Ui, Background).exists(_.hasRemainingTasks)
    while(tasksRemaining && Instant.now().isBefore(start + timeout)) Thread.sleep(10)
    !tasksRemaining
  }
}
