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

import com.waz.model.{PushNotificationEvent, PushNotificationRow, Uid}
import com.waz.service.otr.OtrService
import com.waz.sync.client.PushNotificationEncoded
import com.waz.threading.SerialDispatchQueue

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait PushNotificationEncodedStorage {
  def insertAll(notifications: Seq[PushNotificationEncoded])
               (implicit ec: ExecutionContext): Future[Unit]
  def removeEvent(id: Uid, index: Int): Future[Unit]
  def removeEventsWithIds(ids: Seq[Uid]): Future[Unit]
}

class PushNotificationEncodedStorageImpl(eventsStorage: PushNotificationEventsStorage,
                                         rowStorage: PushNotificationRowStorage,
                                         otrService: OtrService)
  extends PushNotificationEncodedStorage {

  private implicit val dispatcher = new SerialDispatchQueue(name = "PushNotificationEncodedStorage")

  override def insertAll(notifications: Seq[PushNotificationEncoded])
               (implicit ec: ExecutionContext): Future[Unit] =
    Future.sequence(notifications.map(insert)).map(_ => Unit)

  override def removeEvent(id: Uid, index: Int): Future[Unit] = {
    eventsWithId(id).map { size =>
      if(size > 1) {
        eventsStorage.remove((id, index))
      } else {
        eventsStorage.remove((id, index)).flatMap { _ => rowStorage.remove(id) }
      }
    }
  }

  override def removeEventsWithIds(ids: Seq[Uid]): Future[Unit] =
    Future.sequence(ids.map(removeEventsWithId)).map(_ => Unit)

  private def insert(notification: PushNotificationEncoded)
            (implicit ec: ExecutionContext): Future[Unit] = {
    import com.waz.model.JSONHelpers._
    rowStorage.get(notification.id).flatMap {
      case Some(_) =>
        sys.error(s"PushNotification with id ${notification.id} already exists, ignoring")
      case None =>
        val filtered = otrService.filterOtrEventsForOtherClients(notification.events)
        if(filtered.length() != 0) {
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
            .map(_ => Unit)
        } else {
          Future.successful(())
        }
    }
  }

  private def eventsWithId(id: Uid): Future[Int] =
    eventsStorage.events().map(_.apply(id).size)

  private def removeEventsWithId(id: Uid): Future[Unit] =
    for {
      events <- eventsStorage.events()
      _ <- Future.sequence(events(id).map(n => eventsStorage.remove(id, n.index)))
      _ <- rowStorage.remove(id)
    } yield ()

}
