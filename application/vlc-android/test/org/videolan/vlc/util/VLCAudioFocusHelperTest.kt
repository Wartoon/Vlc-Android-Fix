package org.videolan.vlc.util

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VLCAudioFocusHelperTest {

    @Test
    fun callAndRingingModesDeferPlaybackResume() {
        assertTrue(isCallOrRingingAudioMode(AudioManager.MODE_RINGTONE))
        assertTrue(isCallOrRingingAudioMode(AudioManager.MODE_IN_CALL))
        assertTrue(isCallOrRingingAudioMode(AudioManager.MODE_IN_COMMUNICATION))
        assertFalse(isCallOrRingingAudioMode(AudioManager.MODE_NORMAL))
    }
}
