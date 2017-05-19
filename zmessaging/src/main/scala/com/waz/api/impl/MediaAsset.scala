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
package com.waz.api.impl

import java.util

import com.waz.api
import com.waz.api.Asset.LoadCallback
import com.waz.api.MediaAsset.StreamingCallback
import com.waz.api.{KindOfMedia, MediaProvider}
import com.waz.model.messages.media.{EmptyMediaAssetData, MediaAssetData, PlaylistData, TrackData}
import com.waz.service.assets.GlobalRecordAndPlayService.{Content, SpotifyContent, UnauthenticatedContent, UriMediaKey}
import com.waz.threading.Threading
import com.waz.ui.UiModule
import com.waz.utils.events.Signal
import com.waz.utils.wrappers.URI
import org.threeten.bp.Duration

import scala.collection.JavaConverters._
import scala.util.Success

abstract class BaseMediaAsset(protected val data: MediaAssetData) extends api.MediaAsset {
  override def getKind: KindOfMedia = data.kind
  override def getProvider: MediaProvider = data.provider
  override def getTitle: String = data.title
  override def getLinkUri: URI = URI.parse(data.linkUrl)
  override def getDuration: Duration = data.duration.orNull
  override def getArtistName: String = data.artist.map(_.name).getOrElse("")
  override def isStreamable: Boolean = data match {
    case t: TrackData           => t.streamable
    case _: PlaylistData        => data.tracks.exists(_.streamable)
    case _: EmptyMediaAssetData => false
  }
  override def isEmpty: Boolean = data.kind == KindOfMedia.UNKNOWN
}

class MediaAsset(mad: MediaAssetData)(implicit ui: UiModule) extends BaseMediaAsset(mad) {
  import MediaAsset._

  override def getArtwork: api.ImageAsset = data.artwork.map(ui.images.getImageAsset).getOrElse(ImageAsset.Empty)
  override def getArtistAvatar: api.ImageAsset = data.artist.flatMap(_.avatar).map(ui.images.getImageAsset).getOrElse(ImageAsset.Empty)
  override def getTracks: java.util.List[api.MediaAsset] = data.tracks.map(new MediaAsset(_): api.MediaAsset).asJava

  override def prepareStreaming(cb: StreamingCallback): Unit = {
    ui.zms.flatMapFuture(_.richmedia.prepareStreaming(data)).map {
      case Right(uris) => cb.onSuccess(uris.asJava)
      case Left(ErrorResponse(code, msg, label)) => cb.onFailure(code, msg, label)
    } (Threading.Ui)
  }

  override def getPlaybackControls(callback: LoadCallback[api.PlaybackControls]): Unit =
    ui.zms.flatMapFuture(zms => zms.richmedia.prepareStreaming(data)).onComplete {
      case Success(Right(uris)) if uris.nonEmpty && playbackSupportedFrom(data.provider) =>
        val content: Content =
          if (data.provider == MediaProvider.SPOTIFY) SpotifyContent(uris.head, () => ui.zms.flatMapFuture(_.spotifyMedia.authentication.head))
          else UnauthenticatedContent(uris.head)
        callback.onLoaded(new PlaybackControls(UriMediaKey(uris.head), content, _ => Signal.const(mad.duration.getOrElse(Duration.ZERO))))
      case _ => callback.onLoadFailed()
    } (Threading.Ui)
}

object MediaAsset {
  val playbackSupportedFrom = Set(MediaProvider.SOUNDCLOUD, MediaProvider.SPOTIFY)
}

object EmptyMediaAsset extends BaseMediaAsset(EmptyMediaAssetData(MediaProvider.YOUTUBE)) {
  override def getArtwork: api.ImageAsset = ImageAsset.Empty
  override def getArtistAvatar: api.ImageAsset = ImageAsset.Empty
  override def getTracks: util.List[api.MediaAsset] = util.Collections.emptyList()
  override def prepareStreaming(cb: StreamingCallback): Unit = cb.onSuccess(util.Collections.emptyList())
  override def getPlaybackControls(callback: LoadCallback[api.PlaybackControls]): Unit = callback.onLoadFailed()
}
