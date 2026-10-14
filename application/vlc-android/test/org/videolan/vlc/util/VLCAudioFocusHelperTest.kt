package org.videolan.vlc.util

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VLCAudioFocusHelperTest {

    @Test
    fun interruptionWhilePausedDoesNotLatchAndLaterPlaybackCanBePaused() {
        val state = AudioFocusInterruptionState()

        assertFalse(state.onTransientLoss(isPlaying = false))
        assertFalse(state.lossTransient)

        assertTrue(state.onTransientLoss(isPlaying = true))
        assertTrue(state.lossTransient)
        assertTrue(state.shouldResume(resumeEnabled = true))
        state.onResumeStarted()
        assertFalse(state.lossTransient)
    }

    @Test
    fun disabledResumeClearsInterruptionStateForNextEvent() {
        val state = AudioFocusInterruptionState()

        assertTrue(state.onTransientLoss(isPlaying = true))
        assertFalse(state.shouldResume(resumeEnabled = false))
        assertFalse(state.lossTransient)
        assertTrue(state.onTransientLoss(isPlaying = true))
    }

    @Test
    fun permanentLossClearsInterruptionState() {
        val state = AudioFocusInterruptionState()

        assertTrue(state.onTransientLoss(isPlaying = true))
        state.onPermanentLoss()

        assertFalse(state.lossTransient)
        assertFalse(state.shouldResume(resumeEnabled = true))
    }

    @Test
    fun callAndRingingModesDeferPlaybackResume() {
        assertTrue(isCallOrRingingAudioMode(AudioManager.MODE_RINGTONE))
        assertTrue(isCallOrRingingAudioMode(AudioManager.MODE_IN_CALL))
        assertTrue(isCallOrRingingAudioMode(AudioManager.MODE_IN_COMMUNICATION))
        assertFalse(isCallOrRingingAudioMode(AudioManager.MODE_NORMAL))
    }
}
