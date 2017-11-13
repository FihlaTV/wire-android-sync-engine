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
package com.waz.model

import com.waz.sync.client.PushNotificationEncoded
import com.waz.db.Dao
import com.waz.db.Col._
import com.waz.utils.wrappers.{DB, DBCursor}
import org.json.JSONArray

object PushNotificationEncodedData {
  implicit object PushNotificationEncodedDao extends Dao[PushNotificationEncoded, Uid] {
    private val Id = id[Uid]('_id, "PRIMARY KEY").apply(_.id)
    private val Data = text('events).apply(_.events.toString)
    private val Transient = bool('transient)(_.transient)

    override val idCol = Id
    override val table = Table("PushNotifications", Id, Data, Transient)

    override def apply(implicit cursor: DBCursor): PushNotificationEncoded =
      PushNotificationEncoded(Id, new JSONArray(cursor.getString(1)), Transient)

    override def onCreate(db: DB): Unit = {
      super.onCreate(db)
      db.execSQL(s"CREATE INDEX IF NOT EXISTS PushNotification_Id on PushNotifications (${Id.name})")
    }
  }
}
