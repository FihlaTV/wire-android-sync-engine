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
package com.waz.sync.client

import com.waz.ZLog._
import com.waz.model.ConversationData.{ConversationStatus, ConversationType}
import com.waz.model._
import com.waz.sync.client.ConversationsClient.ConversationResponse.{ConversationIdsResponse, ConversationsResult}
import com.waz.threading.Threading
import com.waz.utils.{Json, JsonDecoder, JsonEncoder, RichOption}
import com.waz.znet.ContentEncoder.JsonContentEncoder
import com.waz.znet.Response.{HttpStatus, Status, SuccessHttpStatus}
import com.waz.znet.ZNetClient._
import com.waz.znet._
import org.json.{JSONArray, JSONObject}
import org.threeten.bp.Instant

import scala.util.control.NonFatal

class ConversationsClient(netClient: ZNetClient) {
  import Threading.Implicits.Background
  import com.waz.sync.client.ConversationsClient._

  def loadConversationIds(start: Option[RConvId] = None): ErrorOrResponse[ConversationIdsResponse] =
    netClient.withErrorHandling(s"loadConversationIds(start = $start)", Request.Get(conversationIdsQuery(start))) {
      case Response(SuccessHttpStatus(), ConversationIdsResponse(ids, hasMore), _) => ConversationIdsResponse(ids, hasMore)
    }

  def loadConversations(start: Option[RConvId] = None, limit: Int = ConversationsPageSize): ErrorOrResponse[ConversationsResult] =
    netClient.withErrorHandling(s"loadConversations(start = $start)", Request.Get(conversationsQuery(start, limit))) {
      case Response(SuccessHttpStatus(), ConversationsResult(conversations, hasMore), _) => ConversationsResult(conversations, hasMore)
    }

  def loadConversations(ids: Seq[RConvId]): ErrorOrResponse[Seq[ConversationResponse]] =
    netClient.withErrorHandling(s"loadConversations(ids = $ids)", Request.Get(conversationsQuery(ids = ids))) {
      case Response(SuccessHttpStatus(), ConversationsResult(conversations, _), _) => conversations
    }

  def loadConversation(id: RConvId): ErrorOrResponse[ConversationResponse] =
    netClient.withErrorHandling(s"loadConversation($id)", Request.Get(s"$ConversationsPath/$id")) {
      case Response(SuccessHttpStatus(), ConversationsResult(Seq(conversation), _), _) => conversation
    }

  def postName(convId: RConvId, name: String): ErrorOrResponse[Option[RenameConversationEvent]] =
    netClient.withErrorHandling("postName", Request.Put(s"$ConversationsPath/$convId", Json("name" -> name))) {
      case Response(SuccessHttpStatus(), EventsResponse(event: RenameConversationEvent), _) => Some(event)
    }

  def postConversationState(convId: RConvId, state: ConversationState): ErrorOrResponse[Boolean] =
    netClient.withErrorHandling("postConversationState", Request.Put(s"$ConversationsPath/$convId/self", state)(ConversationState.StateContentEncoder)) {
      case Response(SuccessHttpStatus(), _, _) => true
    }

  def postMemberJoin(conv: RConvId, members: Seq[UserId]): ErrorOrResponse[Option[MemberJoinEvent]] =
    netClient.withErrorHandling("postMemberJoin", Request.Post(s"$ConversationsPath/$conv/members", Json("users" -> Json(members)))) {
      case Response(SuccessHttpStatus(), EventsResponse(event: MemberJoinEvent), _) => Some(event)
      case Response(HttpStatus(Response.Status.NoResponse, _), EmptyResponse, _) => None
    }

  def postMemberLeave(conv: RConvId, user: UserId): ErrorOrResponse[Option[MemberLeaveEvent]] =
    netClient.withErrorHandling("postMemberLeave", Request.Delete(s"$ConversationsPath/$conv/members/$user")) {
      case Response(SuccessHttpStatus(), EventsResponse(event: MemberLeaveEvent), _) => Some(event)
      case Response(HttpStatus(Status.NoResponse, _), EmptyResponse, _) => None
    }

  def postConversation(users: Seq[UserId], name: Option[String] = None): ErrorOrResponse[ConversationResponse] = {
    debug(s"postConversation($users, $name)")
    val payload = JsonEncoder { o =>
      o.put("users", Json(users))
      name.foreach(o.put("name", _))
    }
    netClient.withErrorHandling("postConversation", Request.Post(ConversationsPath, payload)) {
      case Response(SuccessHttpStatus(), ConversationsResult(Seq(conv), _), _) => conv
    }
  }
}

object ConversationsClient {
  private implicit val logTag: LogTag = logTagFor[ConversationsClient]

  val ConversationsPath = "/conversations"
  val ConversationIdsPath = "/conversations/ids"
  val ConversationsPageSize = 100
  val ConversationIdsPageSize = 1000
  val IdsCountThreshold = 32

  def conversationsQuery(start: Option[RConvId] = None, limit: Int = ConversationsPageSize, ids: Seq[RConvId] = Nil): String = {
    val args = (start, ids) match {
      case (None, Nil) =>   Seq("size" -> limit)
      case (Some(id), _) => Seq("size" -> limit, "start" -> id.str)
      case _ =>             Seq("ids" -> ids.mkString(","))
    }
    Request.query(ConversationsPath, args: _*)
  }

  def conversationIdsQuery(start: Option[RConvId]): String =
    Request.query(ConversationIdsPath, ("size", ConversationIdsPageSize) :: start.toList.map("start" -> _.str) : _*)

  case class ConversationResponse(conversation: ConversationData, members: Seq[ConversationMemberData])

  object ConversationResponse {
    import com.waz.utils.JsonDecoder._

    def memberDecoder(convId: ConvId) = new JsonDecoder[Option[ConversationMemberData]] {
      override def apply(implicit js: JSONObject) = {
        if (decodeOptInt('status).forall(s => ConversationStatus(s) == ConversationStatus.Active))
          Some(ConversationMemberData('id, convId))
        else
          None
      }
    }

    def conversationData(js: JSONObject, self: JSONObject) = {
      implicit val jsObj = self

      val id = decodeRConvId('id)(js)
      val convType = ConversationType(decodeInt('type)(js))
      val lastEventTime = decodeISOInstant('last_event_time)(js)
      val renameEvt = if (convType == ConversationType.Group) lastEventTime else Instant.EPOCH

      val state = ConversationState.Decoder(self)

      ConversationData(
        ConvId(id.str), id, decodeOptString('name)(js) filterNot (_.isEmpty), decodeUserId('creator)(js), convType,
        lastEventTime, decodeOptInt('status).fold2(Some(ConversationStatus.Active), i => Some(ConversationStatus(i))),
        Instant.EPOCH, state.muted.getOrElse(false), state.muteTime.getOrElse(lastEventTime), state.archived.getOrElse(false), state.archiveTime.getOrElse(lastEventTime), renameEvent = renameEvt
      )
    }

    implicit lazy val Decoder: JsonDecoder[ConversationResponse] = new JsonDecoder[ConversationResponse] {
      implicit val logTag: LogTag = "ConversationResponse.Decoder"

      override def apply(implicit js: JSONObject): ConversationResponse = {
        debug(s"decoding response: $js")
        val members = js.getJSONObject("members")
        val self = members.getJSONObject("self")

        val conversation = conversationData(js, self)

        ConversationResponse(conversation, array(members.getJSONArray("others"))(memberDecoder(conversation.id)).flatten)
      }
    }

    case class ConversationsResult(conversations: Seq[ConversationResponse], hasMore: Boolean)
    
    object ConversationsResult {

      def unapply(response: ResponseContent): Option[(List[ConversationResponse], Boolean)] = try {
        response match {
          case JsonObjectResponse(js) if js.has("conversations") =>
            Some((array[ConversationResponse](js.getJSONArray("conversations")).toList, decodeBool('has_more)(js)))
          case JsonArrayResponse(js) => Some((array[ConversationResponse](js).toList, false))
          case JsonObjectResponse(js) => Some((List(Decoder(js)), false))
          case _ => None
        }
      } catch {
        case NonFatal(e) =>
          warn(s"couldn't parse conversations response: $response", e)
          warn("json decoding failed", e)
          None
      }
    }

    case class ConversationIdsResponse(ids: Seq[RConvId], hasMore: Boolean)

    object ConversationIdsResponse {

      val idExtractor = { (arr: JSONArray, i: Int) => RConvId(arr.getString(i)) }

      def unapply(response: ResponseContent): Option[(Seq[RConvId], Boolean)] = try {
        response match {
          case JsonObjectResponse(js) if js.has("conversations") => Some((array[RConvId](js.getJSONArray("conversations"), idExtractor), decodeBool('has_more)(js)))
          case JsonArrayResponse(js) => Some((array[RConvId](js, idExtractor), false))
          case _ => None
        }
      } catch {
        case NonFatal(e) =>
          warn(s"couldn't parse conversations response: $response", e)
          None
      }
    }
  }

  object EventsResponse {
    import com.waz.utils.JsonDecoder._

    def unapplySeq(response: ResponseContent): Option[List[ConversationEvent]] = try {
      response match {
        case JsonObjectResponse(js) if js.has("events") => Some(array[ConversationEvent](js.getJSONArray("events")).toList)
        case JsonArrayResponse(js) => Some(array[ConversationEvent](js).toList)
        case JsonObjectResponse(js) => Some(List(implicitly[JsonDecoder[ConversationEvent]].apply(js)))
        case _ => None
      }
    } catch {
      case NonFatal(e) =>
        warn(s"couldn't parse events response $response", e)
        None
    }
  }
}
