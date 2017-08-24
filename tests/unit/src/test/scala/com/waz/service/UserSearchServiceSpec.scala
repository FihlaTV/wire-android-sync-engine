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
package com.waz.service

import com.waz.api.User.ConnectionStatus
import com.waz.content._
import com.waz.model.SearchQuery.Recommended
import com.waz.model._
import com.waz.service.conversation.ConversationsUiService
import com.waz.service.teams.TeamsService
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.SyncServiceHandle
import com.waz.utils.Managed
import com.waz.utils.events.{Signal, SourceSignal}
import com.waz.utils.wrappers.DB
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.collection.generic.CanBuild
import scala.concurrent.Future
import scala.language.higherKinds

class UserSearchServiceSpec extends AndroidFreeSpec {

  val selfId          = UserId()
  val teamId          = Option.empty[TeamId]

  val queryCacheStorage = mock[SearchQueryCacheStorage]
  val userService       = mock[UserService]
  val usersStorage      = mock[UsersStorage]
  val membersStorage    = mock[MembersStorage]
  val teamsService      = mock[TeamsService]
  val sync              = mock[SyncServiceHandle]
  val messagesStorage   = mock[MessagesStorage]
  val convsUi           = mock[ConversationsUiService]
  val timeouts          = new Timeouts

  lazy val users = Map(
    id('a) -> UserData(id('a), "other user 1"),
    id('b) -> UserData(id('b), "other user 2"),
    id('c) -> UserData(id('c), "some name"),
    id('d) -> UserData(id('d), "related user 1").copy(relation = Relation.Second),
    id('e) -> UserData(id('e), "related user 2").copy(relation = Relation.Second),
    id('f) -> UserData(id('f), "other related").copy(relation = Relation.Third),
    id('g) -> UserData(id('g), "friend user 1").copy(connection = ConnectionStatus.ACCEPTED),
    id('h) -> UserData(id('h), "friend user 2").copy(connection = ConnectionStatus.ACCEPTED),
    id('i) -> UserData(id('i), "some other friend").copy(connection = ConnectionStatus.ACCEPTED),
    id('j) -> UserData(id('j), "meep moop").copy(email = Some(EmailAddress("moop@meep.me")))
  )

  def id(s: Symbol) = UserId(s.toString)
  def ids(s: Symbol*) = s.map(id)(breakOut).toSet

  def verifySearch(prefix: String, matches: Set[UserId]) = {
    val query = Recommended(prefix)
    val expected = users.filterKeys(matches.contains).values.toVector
    val querySignal = Signal[Option[SearchQueryCache]]()
    val firstQueryCache = SearchQueryCache(query, Instant.now, None)
    val secondQueryCache = SearchQueryCache(query, Instant.now, Some(matches.toVector))

    (queryCacheStorage.deleteBefore _).expects(*).anyNumberOfTimes().returning(Future.successful({}))

    (queryCacheStorage.optSignal _).expects(query).once().returning(querySignal)
    (usersStorage.find(_: UserData => Boolean, _: DB => Managed[TraversableOnce[UserData]], _: UserData => UserData)(_: CanBuild[UserData, Vector[UserData]]))
      .expects(*, *, *, *).once().returning(Future.successful(expected))

    (queryCacheStorage.updateOrCreate _).expects(query, *, *).once().returning {
      Future.successful(secondQueryCache)
    }

    (sync.syncSearchQuery _).expects(query).once().onCall { _: SearchQuery =>
      Future.successful {
        querySignal ! Some(secondQueryCache)
        SyncId()
      }
    }

    (usersStorage.listSignal _).expects(*).once().returning(Signal.const(expected))

    querySignal ! Some(firstQueryCache)
    result(querySignal.filter(_.contains(firstQueryCache)).head)

    val resSignal = getService.searchUserData(Recommended(prefix)).disableAutowiring()

    result(querySignal.filter(_.contains(secondQueryCache)).head)

    result(resSignal.map(_.keys.toSet).filter(_ == matches).head)
  }

  feature("Recommended people search") {
    scenario("Return search results for name") {
      verifySearch("rel", ids('d, 'e))
    }

    scenario("Return no search results for name") {
      verifySearch("relt", Set.empty[UserId])
    }

    scenario("Return search results for handle") {
      verifySearch("@rel", ids('d, 'e))
    }

    scenario("Return no search results for handle") {
      verifySearch("@relt", Set.empty[UserId])
    }

  }

  feature("Search by searchState") {
    scenario("search for top people"){
      val expected = ids('g, 'h, 'i)

      (queryCacheStorage.deleteBefore _).expects(*).anyNumberOfTimes().returning(Future.successful[Unit]({}))
      (usersStorage.find(_: UserData => Boolean, _: DB => Managed[TraversableOnce[UserData]], _: UserData => UserData)(_: CanBuild[UserData, Vector[UserData]]))
        .expects(*, *, *, *).once().returning(Future.successful(expected.map(users).toVector))

      (userService.acceptedOrBlockedUsers _).expects().returns(Signal.const(Map.empty[UserId, UserData]))
      (messagesStorage.countLaterThan _).expects(*, *).repeated(3).returning(Future.successful(1L))

      val res = getService.search(SearchState("", hasSelectedUsers = false, None), Set.empty[UserId]).map(_.topPeople.map(_.map(_.id).toSet))

      result(res.filter(_.exists(_ == expected)).head)
    }

    scenario("search for local results"){
      val expected = ids('g, 'h)
      val query = Recommended("fr")

      val querySignal = new SourceSignal[Option[SearchQueryCache]]()
      val queryCache = SearchQueryCache(query, Instant.now, Some(Vector.empty[UserId]))

      (queryCacheStorage.deleteBefore _).expects(*).anyNumberOfTimes().returning(Future.successful[Unit]({}))
      (queryCacheStorage.optSignal _).expects(query).once().returning(querySignal)

      (usersStorage.find(_: UserData => Boolean, _: DB => Managed[TraversableOnce[UserData]], _: UserData => UserData)(_: CanBuild[UserData, Vector[UserData]]))
        .expects(*, *, *, *).once().returning(Future.successful(Vector.empty[UserData]))
      (userService.acceptedOrBlockedUsers _).expects().once().returning(Signal.const(expected.map(key => (key -> users(key))).toMap))

      (convsUi.findGroupConversations _).expects(*, *, *).returns(Future.successful(IndexedSeq.empty[ConversationData]))
      (queryCacheStorage.updateOrCreate _).expects(*, *, *).once().returning(Future.successful(queryCache))

      (sync.syncSearchQuery _).expects(query).once().onCall { _: SearchQuery =>
        Future.successful[SyncId] {
          querySignal ! Some(queryCache)
          SyncId()
        }
      }

      (usersStorage.listSignal _).expects(*).once().returning(Signal.const(Vector.empty[UserData]))

      val res = getService.search(SearchState("fr", hasSelectedUsers = false, None), Set.empty[UserId]).map(_.localResults.map(_.map(_.id).toSet))
      querySignal ! None

      result(res.filter(_.exists(_ == expected)).head)
    }

    scenario("search for remote results") {
      val expected = ids('a, 'b)
      val query = Recommended("ot")

      val querySignal = new SourceSignal[Option[SearchQueryCache]]()
      val queryCache = SearchQueryCache(query, Instant.now, Some(expected.toVector))

      (queryCacheStorage.deleteBefore _).expects(*).anyNumberOfTimes().returning(Future.successful[Unit]({}))
      (queryCacheStorage.optSignal _).expects(query).once().returning(querySignal)

      (usersStorage.find(_: UserData => Boolean, _: DB => Managed[TraversableOnce[UserData]], _: UserData => UserData)(_: CanBuild[UserData, Vector[UserData]]))
        .expects(*, *, *, *).once().returning(Future.successful(Vector.empty[UserData]))
      (userService.acceptedOrBlockedUsers _).expects().once().returning(Signal.const(Map.empty[UserId, UserData]))

      (convsUi.findGroupConversations _).expects(*, *, *).returns(Future.successful(IndexedSeq.empty[ConversationData]))
      (queryCacheStorage.updateOrCreate _).expects(*, *, *).once().returning(Future.successful(queryCache))

      (sync.syncSearchQuery _).expects(query).once().onCall { _: SearchQuery =>
        Future.successful[SyncId] {
          querySignal ! Some(queryCache)
          SyncId()
        }
      }

      (usersStorage.listSignal _).expects(expected.toVector).once().returning(Signal.const(expected.map(users).toVector))

      val res = getService.search(SearchState("ot", hasSelectedUsers = false, None), Set.empty[UserId]).map(_.directoryResults.map(_.map(_.id).toSet))

      querySignal ! None

      result(res.filter(_.exists(_ == expected)).head)
    }
  }

  def getService = new UserSearchService(selfId, queryCacheStorage, teamId, userService, usersStorage, teamsService, membersStorage, timeouts, sync, messagesStorage, convsUi)

}
