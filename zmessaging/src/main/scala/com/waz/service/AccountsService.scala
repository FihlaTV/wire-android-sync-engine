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

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.Invitations._
import com.waz.api.impl.ErrorResponse.internalError
import com.waz.api.impl._
import com.waz.api.{ClientRegistrationState, KindOfAccess, KindOfVerification}
import com.waz.client.RegistrationClientImpl.ActivateResult
import com.waz.client.RegistrationClientImpl.ActivateResult.{Failure, PasswordExists}
import com.waz.content.GlobalPreferences.{CurrentAccountPref, FirstTimeWithTeams}
import com.waz.model._
import com.waz.sync.client.InvitationClient.ConfirmedInvitation
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.events.{EventContext, EventStream, RefreshingSignal, Signal}
import com.waz.utils.{RichOption, returning}
import com.waz.znet.Response
import com.waz.znet.Response.Status
import com.waz.znet.ZNetClient._

import scala.collection.mutable
import scala.concurrent.Future

trait AccountsService {
  import AccountsService._

  def accountState(accId: AccountId): Signal[AccountState]

  def loggedInAccounts: Signal[Set[AccountData]]

  def loggedInAccountIds: Signal[Set[AccountId]] = loggedInAccounts.map(_.map(_.id))

  def activeZms: Signal[Option[ZMessaging]]

}

object AccountsService {
  trait AccountState

  case object LoggedOut extends AccountState

  trait Active extends AccountState
  case object InBackground extends Active
  case object InForeground extends Active

  val NoEmailSetWarning = "Account does not have email set - can't request activation code"
}

class AccountsServiceImpl(val global: GlobalModule) extends AccountsService {

  import AccountsService._

  implicit val dispatcher = new SerialDispatchQueue(name = "InstanceService")

  private[waz] implicit val ec: EventContext = EventContext.Global

  private[waz] val accountMap = new mutable.HashMap[AccountId, AccountManager]()

  lazy val context  = global.context
  val prefs         = global.prefs
  val storage       = global.accountsStorage
  val phoneNumbers  = global.phoneNumbers
  val regClient     = global.regClient
  val loginClient   = global.loginClient

  private val firstTimePref = prefs.preference(FirstTimeWithTeams)

  val activeAccountPref = prefs.preference(CurrentAccountPref)

  val loggedInAccounts = firstTimePref.signal.flatMap {
    case false =>
      val changes = EventStream.union(
        storage.onChanged.map(_.map(_.id)),
        storage.onDeleted
      ).map(_.toSet)
      new RefreshingSignal[Set[AccountData], Set[AccountId]](CancellableFuture.lift(storage.findLoggedIn().map(_.toSet)), changes)
    case true => Signal.const(Set.empty[AccountData])
  }

  val zmsInstances = (for {
    ids <- loggedInAccounts.map(_.map(_.id))
    ams <- Signal.future(Future.sequence(ids.map(getOrCreateAccountManager)))
    zs  <- Signal.sequence(ams.map(_.zmessaging).toSeq: _*)
  } yield
    returning(zs.flatten.toSet) { v =>
      verbose(s"Loaded: ${v.size} zms instances for ${ids.size} accounts")
    }).disableAutowiring()

  @volatile private var accountStateSignals = Map.empty[AccountId, Signal[AccountState]]
  override def accountState(accountId: AccountId) = {

    lazy val newSignal: Signal[AccountState] = for {
      selected <- activeAccountPref.signal.map(_.contains(accountId))
      loggedIn <- loggedInAccountIds.map(_.contains(accountId))
      uiActive <- global.lifecycle.uiActive
    } yield
      returning(if (!loggedIn) LoggedOut else if (uiActive && selected) InForeground else InBackground) { state =>
        verbose(s"account state changed: $accountId -> $state: selected: $selected, loggedIn: $loggedIn, uiActive: $uiActive")
      }

    accountStateSignals.getOrElse(accountId, returning(newSignal) { sig =>
      accountStateSignals += accountId -> sig
    })
  }

  lazy val activeAccount = activeAccountPref.signal.flatMap[Option[AccountData]] {
    case None     => Signal.const(None)
    case Some(id) => storage.optSignal(id)
  }

  lazy val activeAccountManager = activeAccountPref.signal.flatMap[Option[AccountManager]] {
    case None     => Signal.const(None)
    case Some(id) => Signal.future(getOrCreateAccountManager(id).map(Some(_)))
  }

  lazy val activeZms = activeAccountManager.flatMap[Option[ZMessaging]] {
    case Some(service) => service.zmessaging
    case None          => Signal.const(None)
  }

  def getActiveAccount = activeAccountPref() flatMap {
    case None     => Future successful None
    case Some(id) => storage.get(id)
  }

  def getActiveAccountManager = activeAccountPref() flatMap {
    case Some(id) => getOrCreateAccountManager(id) map (Some(_))
    case _        => Future successful None
  }

  def getActiveZms = getActiveAccountManager.flatMap {
    case Some(acc) => acc.getZMessaging
    case None      => Future successful None
  }

  private[service] def getOrCreateAccountManager(accountId: AccountId) = flushOtherCredentials.map { _ =>
    verbose(s"getOrCreateAccountManager: $accountId")
    accountMap.getOrElseUpdate(accountId, new AccountManager(accountId, global, this))
  }

  //TODO - why would we ever NOT want to create the account manager if there is a AccountId available for it?
  def getAccountManager(id: AccountId, orElse: Option[AccountManager] = None): Future[Option[AccountManager]] = storage.get(id) flatMap {
    case Some(acc) =>
      verbose(s"getAccountManager($acc)")
      getOrCreateAccountManager(id) map (Some(_))
    case _ =>
      Future successful None
  }

  def getZMessaging(id: AccountId): Future[Option[ZMessaging]] = getOrCreateAccountManager(id).flatMap(_.getZMessaging)

  def logout(flushCredentials: Boolean) = activeAccountManager.head flatMap {
    case Some(account) => account.logout(flushCredentials)
    case None          => Future.successful(())
  }

  def logout(account: AccountId, flushCredentials: Boolean) = {
    activeAccountPref() flatMap { id =>
        for {
          otherAccounts <- loggedInAccounts.map(_.filter(acc => !id.contains(acc.id) && acc.clientRegState == ClientRegistrationState.REGISTERED).map(_.id)).head
          _ <- if (id.contains(account)) setAccount(if (flushCredentials) otherAccounts.headOption else None) else Future.successful(())
          _ <- if (flushCredentials) storage.update(account, _.copy(accessToken = None, cookie = None, password = None, registeredPush = None, pendingEmail = None, pendingPhone = None)) else Future.successful({})
        } yield {}
    }
  }

  def removeCurrentAccount(): Future[Unit] = activeAccountManager.head flatMap {
    case Some(account) =>
      for {
        _ <- storage.update(account.id, _.copy(accessToken = None, cookie = None, password = None, registeredPush = None, pendingEmail = None, pendingPhone = None, clientRegState = ClientRegistrationState.UNKNOWN, code = None))
        _ <- setAccount(None)
      } yield {}
    case None =>
      Future.successful(())
  }

  private def setAccount(acc: Option[AccountId]) = {
    verbose(s"setAccount($acc)")
    activeAccountPref := acc
  }

  /**
    * Logs out of the current account and switches to another specified by the AccountId. If the other cannot be authorized
    * (no cookie) or if anything else goes wrong, we leave the user logged out
    */
  def switchAccount(accountId: AccountId) = {
    verbose(s"switchAccount: $accountId")
    for {
      cur      <- getActiveAccountManager.map(_.map(_.id))
      if !cur.contains(accountId)
      _        <- logout(flushCredentials = false)
      account  <- storage.get(accountId)
      if account.isDefined
      _        <- setAccount(Some(accountId))
      _        <- getOrCreateAccountManager(accountId)
    } yield {}
  }

  def requestVerificationEmail(email: EmailAddress): Unit = loginClient.requestVerificationEmail(email)

  def requestPhoneConfirmationCode(phone: PhoneNumber, kindOfAccess: KindOfAccess): CancellableFuture[ActivateResult] =
    CancellableFuture.lift(phoneNumbers.normalize(phone)) flatMap { normalizedPhone =>
      regClient.requestPhoneConfirmationCode(normalizedPhone.getOrElse(phone), kindOfAccess)
    }

  def requestPhoneConfirmationCall(phone: PhoneNumber, kindOfAccess: KindOfAccess): CancellableFuture[ActivateResult] =
    CancellableFuture.lift(phoneNumbers.normalize(phone)) flatMap { normalizedPhone =>
      regClient.requestPhoneConfirmationCall(normalizedPhone.getOrElse(phone), kindOfAccess)
    }

  def verifyPhoneNumber(phone: PhoneCredentials, kindOfVerification: KindOfVerification): ErrorOrResponse[Unit] =
    CancellableFuture.lift(phoneNumbers.normalize(phone.phone)) flatMap { normalizedPhone =>
      regClient.verifyPhoneNumber(PhoneCredentials(normalizedPhone.getOrElse(phone.phone), phone.code), kindOfVerification)
    }

  //TODO can be removed after a while
  private val flushOtherCredentials = {
    firstTimePref().flatMap {
      case false => Future.successful({})
      case true  =>
        for {
          cur <- activeAccountPref()
          accs <- storage.list()
          _ <- {
            val withoutCurrent = accs.map(_.id).filterNot(cur.contains)
            verbose(s"Flushing accounts: curr: $cur, others: $withoutCurrent")
            storage.updateAll2(withoutCurrent, _.copy(cookie = None, accessToken = None, password = None, registeredPush = None))
          }
          _ <- firstTimePref.update(false)
        } yield {}
    }
  }

  def loginPhone(number: PhoneNumber): Future[Either[ErrorResponse, Unit]] = {

    def requestCode(): Future[Either[ErrorResponse, Unit]] =
      requestPhoneConfirmationCode(number, KindOfAccess.LOGIN).future.map {
        case Failure(error) => Left(error)
        case PasswordExists => Left(ErrorResponse.PasswordExists)
        case _ => Right(())
      }

    for {
      normalizedPhone <- phoneNumbers.normalize(number).map(_.getOrElse(number))
      acc <- storage.findByPhone(normalizedPhone).map(_.getOrElse(AccountData()))
      req <- requestCode()
      updatedAcc  = acc.copy(pendingPhone = Some(normalizedPhone), accessToken = None, cookie = None, password = None, code = None, regWaiting = false)
      _ <- if (req.isRight) storage.updateOrCreate(acc.id, _ => updatedAcc, updatedAcc).map(_ => ()) else Future.successful(())
      _ <- if (req.isRight) setAccount(Some(updatedAcc.id)) else Future.successful(())
    } yield req
  }

  def registerPhone(number: PhoneNumber): Future[Either[ErrorResponse, Unit]] = {
    for {
      normalizedPhone <- phoneNumbers.normalize(number).map(_.getOrElse(number))
      acc <- storage.findByPhone(normalizedPhone).map(_.getOrElse(AccountData()))
      req <- requestPhoneConfirmationCode(number, KindOfAccess.REGISTRATION).future
      updatedAcc = acc.copy(pendingPhone = Some(normalizedPhone), code = None, regWaiting = true)
      _ <- if (req == ActivateResult.Success) storage.updateOrCreate(updatedAcc.id, _ => updatedAcc, updatedAcc) else Future.successful(())
      _ <- if (req == ActivateResult.Success) CancellableFuture.lift(setAccount(Some(acc.id))) else CancellableFuture.successful(())
    } yield req match {
      case Failure(error) => Left(error)
      case PasswordExists => Left(ErrorResponse.PasswordExists)
      case _ => Right(())
    }
  }

  def activatePhoneOnRegister(accountId: AccountId, code: ConfirmationCode): Future[Either[ErrorResponse, Unit]] = {

    def verifyCodeRequest(credentials: PhoneCredentials, accountId: AccountId): Future[Either[ErrorResponse, Unit]] = {
      verifyPhoneNumber(credentials, KindOfVerification.PREVERIFY_ON_REGISTRATION).future.flatMap {
        case Left(errorResponse) =>
          Future.successful(Left(errorResponse))
        case Right(()) =>
          storage.update(accountId, _.copy(phone = Some(credentials.phone), pendingPhone = None, code = Some(code), regWaiting = true)).map( _ => Right(()))
      }
    }

    for {
      Some(acc) <- storage.get(accountId)
      Some(creds) <- acc.pendingPhone.fold2(Future.successful(None), phone => Future.successful(PhoneCredentials(phone, Some(code))).map(Option(_)))
      req <- verifyCodeRequest(creds.asInstanceOf[PhoneCredentials], acc.id)
    } yield req

  }

  def registerNameOnPhone(accountId: AccountId, name: String): Future[Either[ErrorResponse, Unit]] = {
    for {
      acc <- storage.get(accountId)
      req <- acc.fold2(Future.successful(Left(ErrorResponse.InternalError)), accountData => registerOnBackend(accountData, name))
    } yield req
  }

  def loginPhone(accountId: AccountId, code: ConfirmationCode): Future[Either[ErrorResponse, Unit]] = {
    for {
      Some(acc) <- storage.get(accountId)
      req <- loginOnBackend(acc.copy(code = Some(code)))
      _ <- req match {
        case Right(_) =>
          storage.update(accountId, _.copy(phone = acc.pendingPhone, pendingPhone = None))
        case Left(ErrorResponse(Status.Forbidden, _, "pending-activation")) =>
          storage.update(accountId, _.copy(phone = None, pendingPhone = acc.pendingPhone)).map(_ => ())
        case _ =>
          Future.successful(())
      }
    } yield req
  }

  def loginEmail(emailAddress: EmailAddress, password: String): Future[Either[ErrorResponse, Unit]] = {
    for {
      acc <- storage.findByEmail(emailAddress).map(_.getOrElse(AccountData()))
      loginAcc = acc.copy(email = Some(emailAddress), password = Some(password))
      _ <- storage.updateOrCreate(loginAcc.id, _ => loginAcc, loginAcc)
      req <- loginOnBackend(loginAcc)
      _ <- req match {
        case Right (_) =>
          switchAccount(acc.id)
        case Left(ErrorResponse(Status.Forbidden, _, "pending-activation")) =>
          storage.update(loginAcc.id, _.copy(pendingEmail = Some(emailAddress))).map(_ => ())
        case _ => Future.successful(())
      }
    } yield req
  }

  def registerEmail(emailAddress: EmailAddress, password: String, name: String): Future[Either[ErrorResponse, Unit]] = {
    for {
      acc <- storage.findByEmail(emailAddress).map(_.getOrElse(AccountData()))
      registerAcc = acc.copy(pendingEmail = Some(emailAddress), password = Some(password), name = Some(name))
      _ <- storage.updateOrCreate(registerAcc.id, _ => registerAcc, registerAcc)
      req <- registerOnBackend(registerAcc, name)
      _ <- if (req.isRight) switchAccount(registerAcc.id) else Future.successful(())
      _ <- if (req.isRight) storage.update(registerAcc.id, _.copy(pendingEmail = Some(emailAddress))) else Future.successful(())
    } yield req
  }

  /**
    * Methods for the new "creating a team" flow.
    * TODO - we should integrate these better with other registration methods
    * We will leave them separate for now to reduce the risk of breaking other entry points
    */
  def createTeamAccount(teamName: String): Future[AccountId] = {
    for {
      acc <- storage.findByPendingTeamName(teamName).flatMap {
        case Some(a) => Future.successful(a)
        case None    => storage.insert(AccountData(pendingTeamName = Some(teamName)))
      }
      _   <- setAccount(Some(acc.id))
    } yield acc.id
  }

  def updateCurrentAccount(f: AccountData => AccountData): Future[Unit] = {
    activeAccountPref().flatMap {
      case Some(id) => storage.update(id, f).map(_ => ())
      case _ => throw new IllegalStateException("No current account set")
    }
  }

  //For team flow only (for now) - applies to current active account
  def requestActivationCode(email: EmailAddress): ErrorOr[Unit] =
    regClient.requestEmailConfirmationCode(email).future.flatMap {
      case ActivateResult.Success => updateCurrentAccount(_.copy(pendingEmail = Some(email))).map(_ => Right(()))
      case ActivateResult.PasswordExists => Future.successful(Left(internalError("password exists for email activation - this shouldn't happen")))
      case ActivateResult.Failure(err) => Future.successful(Left(err))
    }

  //For team flow only (for now) - applies to current active account
  def verify(code: ConfirmationCode): ErrorOr[Unit] = withActiveAccount { acc =>
    acc.pendingEmail match {
      case Some(e) => for {
        res <- regClient.verifyEmail(e, code).future
        _   <- res match {
          case Right(()) => updateCurrentAccount(_.copy(code = Some(code)))
          case _ => Future.successful(())
        }
      } yield res
      case _ => Future.successful(Left(internalError(s"Current account: ${acc.id} does not have a pending email address. First request an activation code and provide an email address")))
    }
  }

  //For team flow only (for now) - applies to current active account
  def register(): ErrorOr[Unit] = {
    withActiveAccount { acc =>
      regClient.registerTeamAccount(acc).future.flatMap {
        case Right((userInfo, cookie)) =>
          verbose(s"register($acc) done, id: ${acc.id}, user: $userInfo, cookie: $cookie")
          storage.update(acc.id,
            _.updated(userInfo).copy(
              cookie          = cookie,
              regWaiting      = false,
              code            = None,
              firstLogin      = false,
              email           = acc.pendingEmail,
              pendingEmail    = None,
              pendingTeamName = None
            )).map(_ => Right(()))
        case Left(err@ErrorResponse(Response.Status.NotFound, _, "invalid-code")) =>
          info(s"register($acc.id) failed: invalid-code")
          storage.update(acc.id, _.copy(code = None, password = None)).map(_ => Left(err))
        case Left(error) =>
          info(s"register($acc.id) failed: $error")
          Future successful Left(error)
    }}
  }

  private def withActiveAccount[A](f: AccountData => Future[A]): Future[A] = {
    for {
      acc <- getActiveAccount.map(_.getOrElse(throw new IllegalStateException("No current account set")))
      res <- f(acc)
    } yield res
  }

  private def loginOnBackend(accountData: AccountData): Future[Either[ErrorResponse, Unit]] = {
    loginClient.login(accountData).future.flatMap {
      case Right((token, cookie)) =>
        storage.update(accountData.id, _.copy(accessToken = Some(token), cookie = cookie, code = None)).map(_ => Right(()))
      case Left((_, error @ ErrorResponse(Status.Forbidden, _, "pending-activation"))) =>
        verbose(s"account pending activation: ($accountData), $error")
        storage.update(accountData.id, _.copy(cookie = None, accessToken = None, code = None)).map(_ => Left(error))
      case Left((_, error)) =>
        verbose(s"login failed: $error")
        storage.update(accountData.id, _.copy(cookie = None, accessToken = None, code = None)).map(_ => Left(error))
    }
  }

  private def registerOnBackend(accountData: AccountData, name: String): Future[Either[ErrorResponse, Unit]] = {
    regClient.register(accountData, name, None).future.flatMap {
      case Right((userInfo, Some(cookie))) =>
        verbose(s"register($accountData) done, id: ${accountData.id}, user: $userInfo, cookie: $cookie")
        storage.update(accountData.id, _.updated(userInfo).copy(cookie = Some(cookie), regWaiting = false, name = Some(name), code = None, firstLogin = false)).map(_ => Right(()))
      case Right((userInfo, None)) =>
        verbose(s"register($accountData) done, id: ${accountData.id}, user: $userInfo")
        storage.update(accountData.id, _.updated(userInfo).copy(cookie = None, regWaiting = false,  name = Some(name), code = None, firstLogin = false)).map(_ => Right(()))
      case Left(error) =>
        info(s"register($accountData, $name) failed: $error")
        Future successful Left(error)
    }
  }

  def retrieveInvitationDetails(invitation: PersonalToken): Future[InvitationDetailsResponse] = invitation match {
    case token: PersonalInvitationToken =>
      regClient.getInvitationDetails(token).future.map {
        case Right(ConfirmedInvitation(_, name, Left(email), _)) => EmailAddressResponse(name, email.str)
        case Right(ConfirmedInvitation(_, name, Right(phone), _)) => PhoneNumberResponse(name, phone.str)
        case Left(r) => RetrievalFailed(r)
      }
  }

  def generateAccountFromInvitation(invitationDetails: InvitationDetailsResponse, invitation: PersonalInvitationToken): Future[Unit] = {
    invitationDetails match {
      case EmailAddressResponse(name, email) =>
        for {
          acc <- storage.findByEmail(EmailAddress(email)).map(_.getOrElse(AccountData()))
          updated = acc.copy(email = Some(EmailAddress(email)), pendingEmail = None, name = Some(name), invitationToken = Some(invitation))
          _ <- storage.updateOrCreate(acc.id, _ => updated, updated)
        } yield ()
      case PhoneNumberResponse(name, phone) =>
        for {
          acc <- storage.findByPhone(PhoneNumber(phone)).map(_.getOrElse(AccountData()))
          updated = acc.copy(phone = Some(PhoneNumber(phone)), pendingPhone = None, name = Some(name), invitationToken = Some(invitation))
          _ <- storage.updateOrCreate(acc.id, _ => updated, updated)
        } yield ()
      case _ =>
        Future.successful(())
    }
  }

  def clearInvitation(accountId: AccountId): Future[Unit] = {
    storage.update(accountId, _.copy(invitationToken = None)).map(_ => ())
  }

  def setLoggedIn(accountId: AccountId): Future[Unit] = {
    storage.update(accountId, _.copy(firstLogin = false)).map(_ => ())
  }

}
