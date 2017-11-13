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
package com.waz.sync.otr

import java.util.Date

import com.waz.RobolectricUtils
import com.waz.api.impl.ErrorResponse
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericMessage.TextMessage
import com.waz.model._
import com.waz.model.otr.{Client, ClientId, UserClients}
import com.waz.service.otr.OtrServiceImpl
import com.waz.sync.client.MessagesClient
import com.waz.sync.client.MessagesClient.OtrMessage
import com.waz.sync.client.OtrClient.{ClientMismatch, EncryptedContent, MessageResponse}
import com.waz.testutils.{DefaultPatienceConfig, MockZMessaging}
import com.waz.threading.CancellableFuture
import com.waz.utils.events.Signal
import com.waz.znet.ZNetClient.ErrorOrResponse
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future


@Ignore class OtrSyncHandlerSpec extends FeatureSpec with Matchers with BeforeAndAfter with BeforeAndAfterAll with OptionValues with RobolectricTests with RobolectricUtils with ScalaFutures with DefaultPatienceConfig { test =>

  lazy val selfUser = UserData("test")
  lazy val clientId = ClientId()

  lazy val conv = ConversationData(ConvId(), RConvId(), None, UserId(), ConversationType.Group)

  type EncryptRequest = (ConvId, GenericMessage, Boolean)
  var encryptMsgRequests = Seq.empty[EncryptRequest]

  type PostMsgRequest = (RConvId, OtrMessage, Boolean)
  var postMsgRequests = Seq.empty[PostMsgRequest]
  var postMsgResponse: ClientMismatch = ClientMismatch(new Date)

  var encryptedContent: EncryptedContent = EncryptedContent(Map.empty)

  lazy val zms = new MockZMessaging(selfUserId = selfUser.id, clientId = clientId) {

    override lazy val otrService: OtrServiceImpl = new OtrServiceImpl(selfUserId, clientId, otrClientsService, cryptoBox, membersStorage, sync, cache, metadata, otrClientsStorage, prefs) {
      override def encryptMessage(convId: ConvId, msg: GenericMessage, useFakeOnError: Boolean, previous: EncryptedContent, recipients: Option[Set[UserId]]): Future[EncryptedContent] = {
        encryptMsgRequests = encryptMsgRequests :+ (convId, msg, useFakeOnError)
        Future successful encryptedContent
      }
    }

    override lazy val messagesClient: MessagesClient = new MessagesClient(zNetClient) {
      override def postMessage(conv: RConvId, content: OtrMessage, ignoreMissing: Boolean, recipients: Option[Set[UserId]]): ErrorOrResponse[MessageResponse] = {
        postMsgRequests = postMsgRequests :+ (conv, content, ignoreMissing)
        CancellableFuture.successful(if (ignoreMissing || postMsgResponse.missing.isEmpty) Right(MessageResponse.Success(postMsgResponse)) else Right(MessageResponse.Failure(postMsgResponse)))
      }
    }

    override lazy val otrClientsSync: OtrClientsSyncHandler = new OtrClientsSyncHandler(context, accountId, selfUserId, Signal.const(Some(clientId)), otrClient, otrClientsService, otrClientsStorage, cryptoBox, userPrefs) {
      override private[otr] def syncSessions(clients: Map[UserId, Seq[ClientId]]): Future[Option[ErrorResponse]] = Future.successful(None)
    }


    usersStorage.addOrOverwrite(selfUser).futureValue
    otrClientsStorage.insert(UserClients(selfUser.id, Map(clientId -> Client(clientId, ""))))
  }

  lazy val handler = zms.otrSync

  before {
    encryptMsgRequests = Nil
  }

  feature("Error recovery") {

    scenario("Retry three times, use fake content on first retry, and ignore_missing on second") {
      val msg = TextMessage("test", Map.empty)
      val missing = Map(UserId() -> Seq(ClientId()))
      postMsgResponse = ClientMismatch(Map.empty, missing, Map.empty, new Date(1))

      val res = handler.postOtrMessage(conv, msg).futureValue
      res shouldEqual Right(new Date(1))

      val otrMsg = OtrMessage(clientId, encryptedContent)

      encryptMsgRequests shouldEqual Seq((conv.id, msg, false), (conv.id, msg, true), (conv.id, msg, true))
      postMsgRequests shouldEqual Seq((conv.remoteId, otrMsg, false), (conv.remoteId, otrMsg, false), (conv.remoteId, otrMsg, true))
    }
  }

}
