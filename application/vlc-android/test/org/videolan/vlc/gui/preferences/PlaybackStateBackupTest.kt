package org.videolan.vlc.gui.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.tools.KEY_AUDIO_LAST_PLAYLIST
import org.videolan.tools.KEY_CURRENT_AUDIO
import org.videolan.tools.POSITION_IN_AUDIO_LIST
import org.videolan.tools.POSITION_IN_SONG
import org.videolan.tools.Settings

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PlaybackStateBackupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun queueIndexAndMillisecondsRoundTrip() {
        val p = Settings.getInstance(context)
        p.edit().putString(KEY_AUDIO_LAST_PLAYLIST, "smb://server/one.mp3 smb://server/two.mp3")
            .putString(KEY_CURRENT_AUDIO, "smb://server/two.mp3")
            .putInt(POSITION_IN_AUDIO_LIST, 1).putLong(POSITION_IN_SONG, 123456L).commit()
        val backup = PlaybackStateBackup.capture(context)
        p.edit().remove(KEY_AUDIO_LAST_PLAYLIST).remove(KEY_CURRENT_AUDIO)
            .remove(POSITION_IN_AUDIO_LIST).remove(POSITION_IN_SONG).commit()
        assertTrue(PlaybackStateBackup.restore(context, backup))
        assertEquals("smb://server/one.mp3 smb://server/two.mp3", p.getString(KEY_AUDIO_LAST_PLAYLIST, null))
        assertEquals("smb://server/two.mp3", p.getString(KEY_CURRENT_AUDIO, null))
        assertEquals(1, p.getInt(POSITION_IN_AUDIO_LIST, -1))
        assertEquals(123456L, p.getLong(POSITION_IN_SONG, -1))
        assertTrue(p.getBoolean(PlaybackStateBackup.RESTORE_PENDING, false))
    }

    @Test fun badFieldDoesNotPartiallyRestore() {
        val p = Settings.getInstance(context)
        p.edit().putString(KEY_CURRENT_AUDIO, "existing").commit()
        val bad = JSONObject().put("version", 1)
            .put("values", JSONObject().put(KEY_CURRENT_AUDIO, "new").put("unknown", true)).toString()
        try {
            PlaybackStateBackup.restore(context, bad)
            fail("Malformed payload must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals("existing", p.getString(KEY_CURRENT_AUDIO, null))
        }
    }

    @Test fun emptyStateDoesNotCreatePendingPlayback() {
        val p = Settings.getInstance(context)
        p.edit().clear().commit()
        val empty = JSONObject().put("version", 1).put("values", JSONObject()).toString()
        assertFalse(PlaybackStateBackup.restore(context, empty))
        assertFalse(p.getBoolean(PlaybackStateBackup.RESTORE_PENDING, false))
    }
}
