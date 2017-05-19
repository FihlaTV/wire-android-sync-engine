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
package com.waz.testutils

import com.waz.threading.CancellableFuture
import com.waz.utils.wrappers.URI
import com.waz.znet.ContentEncoder.{EmptyRequestContent, RequestContent}
import com.waz.znet.Request._
import com.waz.znet.Response.ResponseBodyDecoder
import com.waz.znet.{AsyncClient, Response, TestClientWrapper}

import scala.concurrent.duration._
import scala.util.matching.Regex

class UnreliableAsyncClient extends AsyncClient(wrapper = TestClientWrapper) {
  @volatile var delayInMillis: Long = 200L
  @volatile var failFor: Option[(Regex, String)] = None

  override def apply(uri: URI, method: String = "GET", body: RequestContent = EmptyRequestContent, headers: Map[String, String] = AsyncClient.EmptyHeaders, followRedirect: Boolean = false, timeout: FiniteDuration = 30.seconds, decoder: Option[ResponseBodyDecoder] = None, downloadProgressCallback: Option[ProgressCallback] = None): CancellableFuture[Response] = {
    CancellableFuture.delay(delayInMillis.millis) flatMap { _ =>
      val fail = failFor exists { failFor =>
        val (uriRegex, failingMethod) = failFor
        failingMethod == method && uriRegex.pattern.matcher(uri.toString).matches
      }
      if (fail) CancellableFuture.successful(Response(Response.HttpStatus(500)))
      else super.apply(uri, method, body, headers, followRedirect, timeout, decoder, downloadProgressCallback)
    }
  }
}
