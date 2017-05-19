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
package com.waz.service.conversation

import com.waz.ZLog._
import com.waz.content.ContentChange.{Added, Removed, Updated}
import com.waz.content.{ContentChange, ConversationStorageImpl}
import com.waz.model.{ConvId, ConversationData}
import com.waz.model.ConversationData.ConversationType
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events
import com.waz.utils.events._

import scala.concurrent.Future


class ConversationsNotifier(convs: ConversationStorageImpl, service: ConversationsService) {
  import ConversationsNotifier._
  import com.waz.utils.events.EventContext.Implicits.global

  val onConversationChanged = EventStream[ConversationData]()

  val selfConversationSignal = new SelfConversationSignal(convs, service.getSelfConversation)

  def conversationEventsStream(filter: ConversationData => Boolean) = new ConversationEventsEventStream(convs, filter)

  convs.convAdded { onConversationChanged ! _ }
  convs.convUpdated { case (_, conv) =>
    verbose(s"convUpdated($conv)")
    onConversationChanged ! conv
  }
}

object ConversationsNotifier {
  implicit val tag: LogTag = logTagFor[ConversationsNotifier]

  val ConversationListOrdering = Ordering.by((c : ConversationData) => (c.convType == ConversationType.Self, c.hasVoice, c.lastEventTime)).reverse
  val ArchivedListOrdering = Ordering.by((c: ConversationData) => c.lastEventTime).reverse

  class ConversationEventsEventStream(convs: ConversationStorageImpl, filter: ConversationData => Boolean) extends events.EventStream[ContentChange[ConvId, _ <: ConversationData]] {

    import com.waz.utils.events.EventContext.Implicits.global
    @volatile var observers = Seq.empty[Subscription]

    override protected def onWire(): Unit = {
      observers = Seq(
        convs.convAdded(conv => if (filter(conv)) publish(Added(conv.id, conv))),
        convs.convDeleted(conv => if (filter(conv)) publish(Removed(conv.id))),
        convs.convUpdated { case (prev, conv) =>
          if (filter(conv) || filter(prev))
            publish(Updated(prev.id, prev, conv))
        }
      )
    }

    override protected def onUnwire(): Unit =
      observers foreach { _.destroy() }
  }

  class SelfConversationSignal(convs: ConversationStorageImpl, getSelf: => Future[Option[ConversationData]]) extends Signal[Option[ConversationData]] {

    import com.waz.utils.events.EventContext.Implicits.global

    implicit val tag: LogTag = logTagFor[SelfConversationSignal]

    private implicit val dispatcher = new SerialDispatchQueue(name = tag)

    private val stream = new ConversationEventsEventStream(convs, { _.convType == ConversationType.Self })
    @volatile var observer = Option.empty[Subscription]

    override protected def onWire(): Unit = {
      observer = Some(stream {
        case Added(_, conv) => publish(Some(conv))
        case Removed(_) => update()
        case Updated(_, prev, conv) =>
          if (isSelf(conv)) publish(Some(conv))
          else if (isSelf(prev)) update()
      })
      update()
    }

    override protected def onUnwire(): Unit = observer.foreach(_.destroy())

    private def isSelf(conv: ConversationData) = conv.convType == ConversationType.Self

    private def update() = getSelf foreach publish
  }
}
