/*
 * ************************************************************************
 *  VLCAudioFocusHelper.kt
 * *************************************************************************
 *  Copyright © 2018 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *
 *  *************************************************************************
 */
package org.videolan.vlc.util

import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.getSystemService
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.resources.AndroidDevices
import org.videolan.tools.AUDIO_DUCKING
import org.videolan.tools.RESUME_PLAYBACK
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.PlaybackService

private const val TAG = "VLCAudioFocusHelper"
private const val CALL_MODE_CHECK_INTERVAL_MS = 1_000L

internal fun isCallOrRingingAudioMode(mode: Int): Boolean =
        mode == AudioManager.MODE_RINGTONE ||
                mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION

/** Tracks only interruptions that actually paused active playback. */
class AudioFocusInterruptionState {
    @Volatile
    var lossTransient = false
        private set
    private var wasPlaying = false

    fun onTransientLoss(isPlaying: Boolean): Boolean {
        if (lossTransient || !isPlaying) return false
        wasPlaying = true
        lossTransient = true
        return true
    }

    fun shouldResume(resumeEnabled: Boolean): Boolean {
        if (lossTransient && wasPlaying && resumeEnabled) return true
        reset()
        return false
    }

    fun onResumeStarted() = reset()

    fun onPermanentLoss() = reset()

    private fun reset() {
        lossTransient = false
        wasPlaying = false
    }
}

@Suppress("DEPRECATION")
class VLCAudioFocusHelper(private val service: PlaybackService) {

    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest
    private var hasAudioFocus = false
    @Volatile
    private var podcastPlaying = false
    private val interruptionState = AudioFocusInterruptionState()
    internal val lossTransient get() = interruptionState.lossTransient

    private val audioFocusListener = createOnAudioFocusChangeListener()

    internal fun changeAudioFocus(acquire: Boolean) {
        if (!this::audioManager.isInitialized) audioManager = service.getSystemService() ?: return

        if (acquire && !service.hasRenderer()) {
            podcastPlaying = service.isPodcastPlaying
            if (!hasAudioFocus || podcastPlaying) {
                val result = requestAudioFocus()
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    audioManager.setParameters("bgm_state=true")
                    hasAudioFocus = true
                }
            }
        } else if (hasAudioFocus) {
            abandonAudioFocus()
            audioManager.setParameters("bgm_state=false")
            hasAudioFocus = false
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun abandonAudioFocus() = if (AndroidUtil.isOOrLater) {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    } else {
        audioManager.abandonAudioFocus(audioFocusListener)
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun requestAudioFocus() = if (AndroidUtil.isOOrLater) {
        val attributes = AudioAttributes.Builder()
                .setContentType(if (service.isVideoPlaying) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .setWillPauseWhenDucked(podcastPlaying)
                .setAudioAttributes(attributes)
                .build()
        audioManager.requestAudioFocus(audioFocusRequest)
    } else {
        audioManager.requestAudioFocus(audioFocusListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }

    private fun createOnAudioFocusChangeListener(): AudioManager.OnAudioFocusChangeListener {
        return object : AudioManager.OnAudioFocusChangeListener {
            private val handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
            private var lossTransientVolume = -1
            private var lossTime: Long = 0
            private var pendingResumeAction: (() -> Unit)? = null

            private val callModeCheckRunnable = object : Runnable {
                override fun run() {
                    val action = pendingResumeAction ?: return
                    if (isCallOrRingingAudioMode(audioManager.mode)) {
                        handler.postDelayed(this, CALL_MODE_CHECK_INTERVAL_MS)
                    } else {
                        pendingResumeAction = null
                        action()
                    }
                }
            }

            override fun onAudioFocusChange(focusChange: Int) {
                /*
             * Pause playback during alerts and notifications
             */
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        if (BuildConfig.DEBUG) Log.i(TAG, "AUDIOFOCUS_LOSS")
                        // A permanent loss must cancel any resume queued for a call ending.
                        cancelPendingResume()
                        interruptionState.onPermanentLoss()
                        // Pause playback
                        changeAudioFocus(false)
                        service.pause()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (BuildConfig.DEBUG) Log.i(TAG, "AUDIOFOCUS_LOSS_TRANSIENT")
                        // Pause playback
                        pausePlayback()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (BuildConfig.DEBUG) Log.i(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK")
                        // Lower the volume
                        if (service.isPlaying) {
                            if (AndroidDevices.isAmazon || podcastPlaying) {
                                pausePlayback()
                            } else if (service.settings.getBoolean(AUDIO_DUCKING, true)) {
                                val volume = service.volume
                                lossTransientVolume = volume
                                service.setVolume(volume / 3)
                            }
                        }
                    }

                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (BuildConfig.DEBUG) Log.i(TAG, "AUDIOFOCUS_GAIN: ")
                        // Resume playback
                        if (lossTransientVolume != -1) {
                            service.setVolume(lossTransientVolume)
                            lossTransientVolume = -1
                        }

                        val resumeEnabled = podcastPlaying || service.settings.getBoolean(RESUME_PLAYBACK, true)
                        if (interruptionState.shouldResume(resumeEnabled)) {
                            if (podcastPlaying) {
                                // If the interruption was short just restart playback
                                val deltaTime = System.currentTimeMillis() - lossTime
                                if (deltaTime > 1_000L) {
                                    resumeWhenCallEnds { schedulePlayback(2_000L) }
                                } else {
                                    resumeWhenCallEnds { resumePlayback() }
                                }
                            } else {
                                resumeWhenCallEnds { resumePlayback() }
                            }
                        }
                    }
                }
            }

            private fun pausePlayback() {
                if (interruptionState.onTransientLoss(service.isPlaying)) {
                    service.pause()
                    lossTime = System.currentTimeMillis()
                }
            }

            private fun resumeWhenCallEnds(action: () -> Unit) {
                handler.removeCallbacks(callModeCheckRunnable)
                if (isCallOrRingingAudioMode(audioManager.mode)) {
                    pendingResumeAction = action
                    handler.postDelayed(callModeCheckRunnable, CALL_MODE_CHECK_INTERVAL_MS)
                } else {
                    pendingResumeAction = null
                    action()
                }
            }

            private fun cancelPendingResume() {
                pendingResumeAction = null
                handler.removeCallbacks(callModeCheckRunnable)
                handler.removeCallbacks(delayedPodcastRunnable)
            }

            private fun schedulePlayback(delay: Long) {
                handler.removeCallbacks(delayedPodcastRunnable)
                handler.postDelayed(delayedPodcastRunnable, delay)
            }

            private val delayedPodcastRunnable = Runnable {
                resumeWhenCallEnds {
                    val position = (service.getTime() - 5_000L).coerceAtLeast(0)
                    resumePlayback()
                    service.seek(position)
                    service.playlistManager.player.updateProgress(position)
                }
            }

            private fun resumePlayback() {
                handler.removeCallbacks(callModeCheckRunnable)
                pendingResumeAction = null
                service.play()
                interruptionState.onResumeStarted()
            }
        }
    }
}
