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
package com.waz.service.push

import java.util.Date

import com.waz.RobolectricUtils
import com.waz.api.impl.ErrorResponse
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.service.Timeouts
import com.waz.sync.client.EventsClient.LoadNotificationsResponse
import com.waz.sync.client.{EventsClient, PushNotification}
import com.waz.testutils.MockZMessaging
import com.waz.threading.CancellableFuture
import com.waz.threading.Threading.Implicits.Background
import com.waz.utils.events.EventContext.Implicits.{global => evc}
import com.waz.utils.events.Signal
import org.scalatest._
import org.threeten.bp.Instant

import scala.concurrent.Await
import scala.concurrent.duration._

class PushServiceSpec extends FeatureSpec with Matchers with BeforeAndAfter with BeforeAndAfterAll with RobolectricTests with RobolectricUtils {
  test =>

  val lastId = Uid()
  val wsConnected = Signal(false)
  var lastNotification = Option.empty[PushNotification]
  var notifications: Either[ErrorResponse, Vector[LoadNotificationsResponse]] = _

  var clientDelay = Duration.Zero
  var requestedSince = None: Option[Uid]
  @volatile var slowSyncRequested = 0

  lazy val zms = new MockZMessaging() {

    override def timeouts: Timeouts = new Timeouts {
      override val webSocket: WebSocket = new WebSocket {
        override def inactivityTimeout: Timeout = 250.millis

        override def connectionTimeout: Timeout = 250.millis
      }
    }

    override lazy val websocket = new WebSocketClientService(context, lifecycle, zNetClient, auth, network, global.backend, clientId, timeouts, pushToken) {
      override val connected = wsConnected
    }

    override lazy val eventsClient: EventsClient = new EventsClient(zNetClient) {
      override def loadNotifications(since: Option[Uid], client: ClientId, pageSize: Int) = {
        requestedSince = since
        CancellableFuture.delayed(clientDelay)(test.notifications.right map (_.map { n =>
          onNotificationsPageLoaded ! n
          n.notifications.lastOption map (_.id)
        }.last))
      }

      override def loadLastNotification(client: ClientId) = CancellableFuture.delayed(clientDelay)(Right(lastNotification))
    }

    pushSignals.onSlowSyncNeeded { _ => slowSyncRequested += 1 }
  }

  lazy val service = zms.push

  before {
    requestedSince = None
    slowSyncRequested = 0
    clientDelay = Duration.Zero
    lastNotification = Some(PushNotification(lastId, Nil))
    notifications = Right(Vector(LoadNotificationsResponse(Vector.empty, lastIdWasFound = false, Some(Instant.now))))
  }

  after {
    wsConnected ! false
    awaitUi(50.millis)
  }

  def lastNotificationId = Await.result(service.lastNotification.lastNotificationId(), 5.seconds)

  def lastNotificationId_=(id: Option[Uid]) = Await.result(service.lastNotification.idPref := id, 5.seconds)

  feature("last notification Id") {

    scenario("store last notification Id on new event") {
      wsConnected ! true
      withDelay {
        lastNotificationId should be('defined)
      }
      val id = Uid()
      service.onPushNotification(PushNotification(id, Seq(MemberJoinEvent(RConvId(), new Date, UserId(), Nil))))
      withDelay {
        lastNotificationId shouldEqual Some(id)
      }
    }

    scenario("don't store id on transient notification") {
      lastNotificationId = None
      wsConnected ! true
      withDelay {
        lastNotificationId should be('defined)
      }
      service.onPushNotification(PushNotification(Uid(), Nil, transient = true))
      awaitUi(1.second)
      lastNotificationId shouldEqual Some(lastId)
    }

    scenario("don't update id on otr notification not intended for us") {
      lastNotificationId = None
      wsConnected ! true
      withDelay {
        lastNotificationId should be('defined)
      }
      service.onPushNotification(PushNotification(Uid(), Seq(OtrMessageEvent(RConvId(), new Date, UserId(), ClientId(), ClientId(), Array.empty))))
      awaitUi(1.second)
      lastNotificationId shouldEqual Some(lastId)
    }
  }

  feature("/notifications") {

    scenario("fetch last notification when there is no local last notification id") {
      wsConnected ! true
      withDelay {
        slowSyncRequested shouldEqual 1
        lastNotificationId shouldEqual Some(lastId)
      }
    }

    scenario("fetch notifications and update last id") {
      lastNotificationId = Some(lastId)
      val notification1 = PushNotification(Uid(), Nil)
      val notification2 = PushNotification(Uid(), Nil)
      notifications = Right(Vector(
        LoadNotificationsResponse(Vector(notification1), lastIdWasFound = true, Some(Instant.now)),
        LoadNotificationsResponse(Vector(notification2), lastIdWasFound = true, Some(Instant.now))))
      wsConnected ! true

      withDelay {
        requestedSince shouldEqual Some(lastId)
        lastNotificationId shouldEqual Some(notification2.id)
        slowSyncRequested shouldEqual 0
      }
    }

    scenario("request slow sync if /notifications returns 404 with notifications") {
      lastNotificationId = Some(lastId)
      val notification1 = PushNotification(Uid(), Nil)
      val notification2 = PushNotification(Uid(), Nil)
      notifications = Right(Vector(
        LoadNotificationsResponse(Vector(notification1), lastIdWasFound = false, Some(Instant.now)),
        LoadNotificationsResponse(Vector(notification2), lastIdWasFound = true, Some(Instant.now))))
      wsConnected ! true

      withDelay {
        requestedSince shouldEqual Some(lastId)
        lastNotificationId shouldEqual Some(lastId)
        slowSyncRequested shouldEqual 1
      }
    }

    scenario("request slow sync and fetch last notification if /notifications returns 404 without notifications") {
      lastNotificationId = Some(lastId)
      notifications = Right(Vector(LoadNotificationsResponse(Vector.empty, lastIdWasFound = false, Some(Instant.now))))
      wsConnected ! true

      withDelay {
        requestedSince shouldEqual Some(lastId)
        lastNotificationId shouldEqual Some(lastId)
        slowSyncRequested shouldEqual 1
      }
    }

    scenario("request slow sync and fetch last notification id if /notifications fails completely") {
      lastNotificationId = Some(lastId)
      notifications = Left(ErrorResponse(500, "", ""))
      wsConnected ! true

      withDelay {
        requestedSince shouldEqual Some(lastId)
        lastNotificationId shouldEqual Some(lastId)
        slowSyncRequested shouldEqual 1
      }
    }
  }
}
