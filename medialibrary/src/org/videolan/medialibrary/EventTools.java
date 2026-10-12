/*
 * ************************************************************************
 *  EventTools.java
 * *************************************************************************
 * Copyright © 2023 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.medialibrary;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.videolan.medialibrary.interfaces.media.MediaWrapper;

public final class EventTools {
    private static EventTools sInstance;
    public final LiveData<MediaWrapper> lastThumb = new QueuedEvent<>();

    /** Keep every native thumbnail completion; MutableLiveData.postValue coalesces pending values. */
    private static final class QueuedEvent<T> extends MutableLiveData<T> {
        private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean dispatchScheduled = new AtomicBoolean(false);
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final Runnable dispatchNext = new Runnable() {
            @Override
            public void run() {
                T value = queue.poll();
                if (value != null) setValue(value);
                if (queue.isEmpty()) {
                    dispatchScheduled.set(false);
                    if (!queue.isEmpty()) scheduleDispatch();
                } else {
                    mainHandler.post(this);
                }
            }
        };

        @Override
        public void postValue(T value) {
            if (value == null) return;
            queue.add(value);
            scheduleDispatch();
        }

        private void scheduleDispatch() {
            if (dispatchScheduled.compareAndSet(false, true)) mainHandler.post(dispatchNext);
        }
    }


    public static EventTools getInstance() {
        if (sInstance == null) {
            sInstance = new EventTools();
        }
        return sInstance;
    }

}