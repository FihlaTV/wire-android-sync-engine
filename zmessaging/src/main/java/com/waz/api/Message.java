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
package com.waz.api;

import android.os.Parcel;
import android.os.Parcelable;
import com.waz.service.ZMessaging$;

public interface Message extends UiObservable, Parcelable {

    Parcelable.Creator<Message> CREATOR = new Parcelable.Creator<Message>() {
        @Override
        public Message createFromParcel(Parcel source) {
            return ZMessaging$.MODULE$.currentUi().messages().cachedOrFromParcel(source);
        }

        @Override
        public Message[] newArray(int size) {
            return new Message[size];
        }
    };

    enum Status {
        DEFAULT, SENT, PENDING, DELETED, FAILED, FAILED_READ, DELIVERED;

        public boolean isFailed() {
            return this == FAILED;
        }
    }

    enum Type {
        TEXT, TEXT_EMOJI_ONLY,
        //TODO rename to IMAGE_ASSET
        ASSET, ANY_ASSET, VIDEO_ASSET, AUDIO_ASSET,

        KNOCK, MEMBER_JOIN, RICH_MEDIA, LOCATION, RECALLED,

        MEMBER_LEAVE, CONNECT_REQUEST, CONNECT_ACCEPTED, RENAME,

        MISSED_CALL, SUCCESSFUL_CALL,

        OTR_ERROR, OTR_IDENTITY_CHANGED, OTR_VERIFIED, OTR_UNVERIFIED, OTR_DEVICE_ADDED, OTR_MEMBER_ADDED,

        STARTED_USING_DEVICE, HISTORY_LOST,

        UNKNOWN
    }

    interface Part {
        enum Type {
            TEXT, TEXT_EMOJI_ONLY, ASSET, ANY_ASSET, YOUTUBE, SOUNDCLOUD, TWITTER, SPOTIFY, WEB_LINK, GOOGLE_MAPS
        }
    }

    String getId();

    ImageAsset getImage();

    /**
     * Returns users (including self) that like this message.
     */
    User[] getLikes();


    boolean isEphemeral();

    boolean isExpired();

    /**
     * Retry sending a previously failed message.
     */
    void retry();
}
