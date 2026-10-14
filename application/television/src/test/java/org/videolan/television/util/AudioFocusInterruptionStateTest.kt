package org.videolan.television.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.util.AudioFocusInterruptionState

class AudioFocusInterruptionStateTest {
    @Test
    fun pausedPlaybackDoesNotLatchNextInterruption() {
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
    fun disabledResumeClearsStateBeforeFollowingInterruption() {
        val state = AudioFocusInterruptionState()

        assertTrue(state.onTransientLoss(isPlaying = true))
        assertFalse(state.shouldResume(resumeEnabled = false))
        assertFalse(state.lossTransient)
        assertTrue(state.onTransientLoss(isPlaying = true))
    }

    @Test
    fun permanentLossClearsResumeEligibility() {
        val state = AudioFocusInterruptionState()

        assertTrue(state.onTransientLoss(isPlaying = true))
        state.onPermanentLoss()

        assertFalse(state.lossTransient)
        assertFalse(state.shouldResume(resumeEnabled = true))
    }
}
