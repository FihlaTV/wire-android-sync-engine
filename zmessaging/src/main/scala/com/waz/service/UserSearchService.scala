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

import com.waz.ZLog._
import com.waz.content.{CommonConnectionsStorage, MessagesStorageImpl, SearchQueryCacheStorage, UsersStorageImpl}
import com.waz.model.SearchQuery.{Recommended, RecommendedHandle, TopPeople}
import com.waz.model.UserData.{ConnectionStatus, UserDataDao}
import com.waz.model.{SearchQuery, _}
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.UserSearchClient.UserSearchEntry
import com.waz.threading.Threading
import com.waz.utils.Locales.currentLocaleOrdering
import com.waz.utils._
import com.waz.utils.events._
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future
import scala.concurrent.Future.traverse
import scala.concurrent.duration._

class UserSearchService(queryCache: SearchQueryCacheStorage, commonConnsStorage: CommonConnectionsStorage,
                        userService: UserServiceImpl, usersStorage: UsersStorageImpl, timeouts: Timeouts, sync: SyncServiceHandle, messages: MessagesStorageImpl) {

  import Threading.Implicits.Background
  import com.waz.service.UserSearchService._
  import timeouts.search._

  ClockSignal(1.day)(i => queryCache.deleteBefore(i - cacheExpiryTime))(EventContext.Global)

  def searchUserData(query: SearchQuery, limit: Option[Int] = None): Signal[SeqMap[UserId, UserData]] =
    queryCache.optSignal(query).flatMap {
      case _ if query == TopPeople =>
        verbose(s"$query local search")
        val connectedUsers = usersStorage.find[UserData, Vector[UserData]](topPeoplePredicate, db => UserDataDao.topPeople(db), identity)
        val usersAndMessageCount = connectedUsers.flatMap(users => Future.sequence(users.map(u => messages.countLaterThan(ConvId(u.id.str), Instant.now - topPeopleMessageInterval).map(count => (u, count)))))
        Signal.future(usersAndMessageCount.map(_.filter(_._2 > 0).sortBy(_._2)(Ordering[Long].reverse).take(MaxTopPeople).map(_._1)))
      case r if r.forall(cached => (cacheExpiryTime elapsedSince cached.timestamp) || cached.entries.isEmpty) =>
        verbose(s"no cached entries for query $query")

        def fallbackToLocal = query match {
          case Recommended(prefix) =>
            usersStorage.find[UserData, Vector[UserData]](recommendedPredicate(prefix, withinThreeLevels), db => UserDataDao.recommendedPeople(prefix)(db), identity)
          case RecommendedHandle(prefix) =>
            usersStorage.find[UserData, Vector[UserData]](recommendedHandlePredicate(prefix), db => UserDataDao.recommendedPeople(prefix)(db), identity)
          case _ => Future.successful(Vector())
        }

        fallbackToLocal.map(_.sortBy(_.name)(currentLocaleOrdering)).flatMap { users =>
          lazy val fresh = SearchQueryCache(query, Instant.now, Some(users.map(_.id)))
          def update(q: SearchQueryCache): SearchQueryCache = if ((cacheExpiryTime elapsedSince q.timestamp) || q.entries.isEmpty) fresh else q

          queryCache.updateOrCreate(query, update, fresh)
        }.flatMap(_ => sync.syncSearchQuery(query)).logFailure()

        Signal.empty[Vector[UserData]]
      case Some(cached) =>
        verbose(s"query $query cached: ${cached.entries.map(_.size)} (${cached.timestamp})")
        if (cacheRefreshInterval elapsedSince cached.timestamp) queryCache.getOrCreate(query, SearchQueryCache(query, Instant.now, None)).flatMap(_ => sync.syncSearchQuery(query)).logFailure()

        //The common connections count will eventually come from the sync, therefore if we return the cached results with a old timestamp it will
        //unnecessarily attempt to refresh the cc with the search/common request for each result.
        val refreshCachedCommonTimestamps = cached.entries.fold2(Future.successful(Vector()), ids => Future.traverse(ids)(id => updateCommonConnectionsTimestamp(id)))
        cached.entries match {
          case Some(ids) if !(cacheRefreshInterval elapsedSince cached.timestamp) => Signal.future(refreshCachedCommonTimestamps).flatMap( _ => usersStorage.listSignal(ids))
          case _ => Signal.const(Vector.empty[UserData])
        }
    }.map { users =>
      query match {
        case TopPeople =>
          users filter topPeoplePredicate
        case Recommended(prefix) =>
          users filter recommendedPredicate(prefix, atAnyLevel)
        case RecommendedHandle(prefix) =>
          users filter recommendedHandlePredicate(prefix)
        case _ =>
          users
      }
    }.map(users => SeqMap(limit.fold2(users, users.take))(_.id, identity))

  private val topPeoplePredicate: UserData => Boolean = u => ! u.deleted && u.connection == ConnectionStatus.Accepted
  private def recommendedPredicate(prefix: String, levels: Set[Relation]): UserData => Boolean = {
    val key = SearchKey(prefix)
    u => ! u.deleted && ! u.isConnected && ((key.isAtTheStartOfAnyWordIn(u.searchKey) && levels(u.relation)) || u.email.exists(_.str == prefix) || u.handle.exists(_.containsQuery(prefix)))
  }
  private def recommendedHandlePredicate(prefix: String): UserData => Boolean = {
    u => ! u.deleted && ! u.isConnected && u.handle.exists(_.containsQuery(prefix))
  }
  private val withinThreeLevels = Set(Relation.First, Relation.Second, Relation.Third)
  private val atAnyLevel = Relation.values.toSet

  def updateSearchResults(query: SearchQuery, results: Seq[UserSearchEntry]): Future[Unit] = {
    def updating(ids: Vector[UserId])(cached: SearchQueryCache) = cached.copy(query, Instant.now, if (ids.nonEmpty || cached.entries.isEmpty) Some(ids) else cached.entries)

    for {
      updated <- userService.updateUsers(results)
      _       <- userService.syncIfNeeded(updated.toSeq: _*)
      _       <- traverse(results)(u => u.commonCount.mapFuture(c => updateCommonConnections(u.id, c, u.common)))
      ids      = results.map(_.id)(breakOut): Vector[UserId]
      _        = verbose(s"updateSearchResults($query, $ids)")
      _       <- queryCache.updateOrCreate(query, updating(ids), SearchQueryCache(query, Instant.now, Some(ids)))
    } yield ()
  }

  private def updateCommonConnections(user: UserId, totalCount: Int, connections: Seq[UserId]): Future[CommonConnectionsData] =
    commonConnsStorage.updateOrCreate(user, { prev =>
      val merged = if (totalCount == connections.size) connections else {
        val cs = connections.toSet
        connections ++ prev.connections.filterNot(cs)
      }
      prev.copy(totalCount = totalCount, connections = merged, timestamp = Instant.now)
    }, CommonConnectionsData(user, totalCount, connections))

  private def updateCommonConnectionsTimestamp(user: UserId): Future[Option[CommonConnectionsData]] =
    commonConnsStorage.update(user, {
      _.copy(timestamp = Instant.now)
    }).map(_.map(_._2))
}

object UserSearchService {
  private implicit val tag: LogTag = logTagFor[UserSearchService]
  val MinCommonConnections = 4
  val MaxTopPeople = 10
}
