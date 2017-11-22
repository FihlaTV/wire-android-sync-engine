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

import com.waz.model.{PushNotificationEvent, PushNotificationRow}
import com.waz.sync.client.PushNotificationEncoded

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class PushNotificationEncodedStorage(eventsStorage: PushNotificationEventsStorage,
                                     rowStorage: PushNotificationRowStorage) {

  def insertAll(notifications: Seq[PushNotificationEncoded])
               (implicit ec: ExecutionContext): Future[Unit] =
    Future.sequence(notifications.map(insert)).map(_ => Unit)

  def insert(notification: PushNotificationEncoded)
            (implicit ec: ExecutionContext): Future[PushNotificationRow] = {
    import com.waz.model.JSONHelpers._
    //TODO: don't write anything if notification already exists
    rowStorage
      .insert(PushNotificationRow(notification.id, notification.transient))
      .andThen {
        case Success(_) => eventsStorage.insertAll(
          notification
            .events
            .JSONArrayToVector
            .zipWithIndex
            .map { case (obj, index) =>
              PushNotificationEvent(notification.id, index, decrypted = false, obj) })
        case Failure(e) => sys.error(s"Failed to insert PushNotification into DB: ${e.toString}")
      }
  }
}
