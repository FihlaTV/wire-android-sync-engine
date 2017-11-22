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

import android.content.Context
import com.waz.content.Database
import com.waz.model.PushNotificationEvents.PushNotificationEventsDao
import com.waz.model.{PushNotificationEvent, Uid}
import com.waz.service.push.PushNotificationEventsStorage.PlainWriter
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.{CachedStorage, CachedStorageImpl, TrimmingLruCache}

import scala.concurrent.Future


object PushNotificationEventsStorage {
  type PlainWriter = Array[Byte] => Future[Unit]
}

trait PushNotificationEventsStorage extends CachedStorage[(Uid, Int), PushNotificationEvent] {
  def events(): Future[Map[Uid, Seq[PushNotificationEvent]]]
  def setAsDecrypted(id: Uid, index: Int): Future[Unit]
  def getPlainWriter(id: Uid, index: Int): PlainWriter
}

class PushNotificationEventsStorageImpl(context: Context, storage: Database)
  extends CachedStorageImpl[(Uid, Int), PushNotificationEvent](new TrimmingLruCache(context, Fixed(100)),
    storage)(PushNotificationEventsDao)
    with PushNotificationEventsStorage {

    override def events(): Future[Map[Uid, Seq[PushNotificationEvent]]] =
      list().map(_.groupBy(_.pushId))

    override def setAsDecrypted(id: Uid, index: Int): Future[Unit] = {
      update((id, index), u => u.copy(decrypted = true)).map {
        case None => Future.failed(sys.error(s"Failed to set event at index $index with id $id as decrypted"))
        case _ => Unit
      }
    }

    override def getPlainWriter(id: Uid, index: Int): PlainWriter =
      (plain: Array[Byte]) =>
        update((id, index), r => r.copy(decrypted = true, plain = Some(plain))).map(_ => Unit)
}
