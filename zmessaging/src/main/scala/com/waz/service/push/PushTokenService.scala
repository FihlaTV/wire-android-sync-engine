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

import com.waz.HockeyApp
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.verbose
import com.waz.content.GlobalPreferences.{CurrentAccountPref, PushEnabledKey}
import com.waz.content.{AccountsStorage, GlobalPreferences}
import com.waz.model.{AccountId, PushToken, PushTokenRemoveEvent}
import com.waz.service.{EventScheduler, ZmsLifecycle}
import com.waz.sync.SyncServiceHandle
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{EventContext, EventStream, Signal}
import com.waz.utils.wrappers.{GoogleApi, Localytics}

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Responsible for deciding when to generate and register push tokens and whether they should be active at all.
  */
class PushTokenService(googleApi: GoogleApi,
                       prefs:     GlobalPreferences,
                       lifeCycle: ZmsLifecycle,
                       accountId: AccountId,
                       accounts:  AccountsStorage,
                       sync:      SyncServiceHandle) {
  implicit val dispatcher = new SerialDispatchQueue(name = "PushTokenDispatchQueue")

  private implicit val ev = EventContext.Global


  val pushEnabled    = prefs.preference(PushEnabledKey)
  val currentAccount = prefs.preference(CurrentAccountPref)
  val currentToken   = prefs.preference(GlobalPreferences.PushToken)

  val onTokenRefresh = EventStream[Option[PushToken]]()

  onTokenRefresh(setNewToken(_))

  private val shouldGenerateNewToken = for {
    play    <- googleApi.isGooglePlayServicesAvailable
    current <- currentToken.signal
  } yield play && current.isEmpty

  shouldGenerateNewToken.on(dispatcher) {
    case true => setNewToken()
    case _ =>
  }

  //None if user is not logged in.
  private val loggedInAccount = currentAccount.signal.map(v => if (v.isEmpty) None else Some(AccountId(v)))

  private val userToken = accounts.signal(accountId).map(_.registeredPush)

  val pushActive = (for {
    push           <- pushEnabled.signal                      if push
    play           <- googleApi.isGooglePlayServicesAvailable if play
    lcActive       <- lifeCycle.active                        if !lcActive
    current        <- currentToken.signal                     if current.isDefined
    userRegistered <- userToken.map(_ == current)
  } yield userRegistered)
    .orElse(Signal.const(false))

  val eventProcessingStage = EventScheduler.Stage[PushTokenRemoveEvent] { (_, events) =>
    currentToken().flatMap {
      case Some(t) if events.exists(_.token == t) =>
        verbose("Clearing all push tokens in response to backend event")
        googleApi.deleteAllPushTokens()
        currentToken := None
      case _ => Future.successful({})
    }
  }

  private def setNewToken(token: Option[PushToken] = None): Future[Unit] = try {
    val t = token.orElse(googleApi.getPushToken)
    t.foreach { t =>
      Localytics.setPushDisabled(false)
      Localytics.setPushRegistrationId(t.str)
    }
    verbose(s"Setting new push token: $t")
    currentToken := t
  } catch {
    case NonFatal(ex) => Future.successful {
      HockeyApp.saveException(ex, s"unable to set push token")
    }
  }

  //on dispatcher prevents infinite register loop
  (for {
    Some(id)    <- loggedInAccount if id == accountId
    userToken   <- userToken
    globalToken <- currentToken.signal
  } yield (globalToken, userToken)).on(dispatcher) {
    case (Some(glob), Some(user)) if glob != user =>
      sync.deletePushToken(user)
      sync.registerPush(glob)
    case (Some(glob), None) =>
      sync.registerPush(glob)
    case (None, Some(user)) =>
      sync.deletePushToken(user)
    case _ => //do nothing
  }

  def onTokenRegistered(token: PushToken): Future[Unit] = {
    verbose(s"onTokenRegistered: $accountId, $token")
    for {
      Some(id) <- loggedInAccount.head if id == accountId
      _        <- accounts.update(id, _.copy(registeredPush = Some(token)))
    } yield {}
  }
}

object PushTokenService {
  case class PushSenderId(str: String) extends AnyVal
}
