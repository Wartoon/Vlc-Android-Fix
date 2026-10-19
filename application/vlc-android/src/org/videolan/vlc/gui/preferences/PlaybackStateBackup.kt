package org.videolan.vlc.gui.preferences

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.AUDIO_SHUFFLING
import org.videolan.tools.KEY_AUDIO_LAST_PLAYLIST
import org.videolan.tools.KEY_CURRENT_AUDIO
import org.videolan.tools.KEY_CURRENT_AUDIO_RESUME_ARTIST
import org.videolan.tools.KEY_CURRENT_AUDIO_RESUME_THUMB
import org.videolan.tools.KEY_CURRENT_AUDIO_RESUME_TITLE
import org.videolan.tools.KEY_CURRENT_MEDIA
import org.videolan.tools.KEY_CURRENT_MEDIA_IS_AUDIO
import org.videolan.tools.KEY_CURRENT_MEDIA_RESUME
import org.videolan.tools.KEY_MEDIA_LAST_PLAYLIST
import org.videolan.tools.KEY_MEDIA_LAST_PLAYLIST_RESUME
import org.videolan.tools.MEDIA_SHUFFLING
import org.videolan.tools.POSITION_IN_AUDIO_LIST
import org.videolan.tools.POSITION_IN_MEDIA
import org.videolan.tools.POSITION_IN_MEDIA_LIST
import org.videolan.tools.POSITION_IN_SONG
import org.videolan.tools.Settings
import org.videolan.tools.VIDEO_PAUSED
import org.videolan.vlc.PlaybackService

/** Full-backup-only state. Do not expose excluded playback keys in normal settings exports. */
object PlaybackStateBackup {
    const val ENTRY = "playback/state.json"
    const val RESTORE_PENDING = "vlc_full_backup_playback_pending"

    private val textKeys = listOf(
        KEY_CURRENT_AUDIO, KEY_CURRENT_MEDIA, KEY_CURRENT_MEDIA_RESUME,
        KEY_AUDIO_LAST_PLAYLIST, KEY_MEDIA_LAST_PLAYLIST, KEY_MEDIA_LAST_PLAYLIST_RESUME,
        KEY_CURRENT_AUDIO_RESUME_TITLE, KEY_CURRENT_AUDIO_RESUME_ARTIST, KEY_CURRENT_AUDIO_RESUME_THUMB
    )
    private val intKeys = listOf(POSITION_IN_AUDIO_LIST, POSITION_IN_MEDIA_LIST)
    private val longKeys = listOf(POSITION_IN_SONG, POSITION_IN_MEDIA)
    private val booleanKeys = listOf(KEY_CURRENT_MEDIA_IS_AUDIO, AUDIO_SHUFFLING, MEDIA_SHUFFLING, VIDEO_PAUSED)

    /** Must be called on the UI thread, before dispatching ZIP creation to IO. */
    fun capture(context: Context): String {
        val prefs = Settings.getInstance(context)
        val values = JSONObject()
        textKeys.forEach { if (prefs.contains(it)) values.put(it, prefs.getString(it, "")) }
        intKeys.forEach { if (prefs.contains(it)) values.put(it, prefs.getInt(it, 0)) }
        longKeys.forEach { if (prefs.contains(it)) values.put(it, prefs.getLong(it, 0L)) }
        booleanKeys.forEach { if (prefs.contains(it)) values.put(it, prefs.getBoolean(it, false)) }

        val service = PlaybackService.instance
        val current = service?.currentMediaWrapper
        if (service != null && current != null) {
            val queue = service.playlistManager.getMediaList()
            val index = service.currentMediaPosition
            if (index in queue.indices && queue.all { it.uri != null }) {
                val locations = queue.joinToString(" ") { it.uri.toString() }
                val audio = service.playlistManager.isAudioList() || current.hasFlag(MediaWrapper.MEDIA_FORCE_AUDIO)
                values.put(KEY_CURRENT_MEDIA_IS_AUDIO, audio)
                values.put(KEY_CURRENT_MEDIA_RESUME, current.location)
                values.put(KEY_MEDIA_LAST_PLAYLIST_RESUME, locations)
                values.put(if (audio) KEY_CURRENT_AUDIO else KEY_CURRENT_MEDIA, current.location)
                values.put(if (audio) KEY_AUDIO_LAST_PLAYLIST else KEY_MEDIA_LAST_PLAYLIST, locations)
                values.put(if (audio) POSITION_IN_AUDIO_LIST else POSITION_IN_MEDIA_LIST, index)
                values.put(if (audio) POSITION_IN_SONG else POSITION_IN_MEDIA, service.getTime().coerceAtLeast(0L))
                if (audio) {
                    values.put(KEY_CURRENT_AUDIO_RESUME_TITLE, current.title ?: "")
                    values.put(KEY_CURRENT_AUDIO_RESUME_ARTIST, current.artistName ?: "")
                    values.put(KEY_CURRENT_AUDIO_RESUME_THUMB, current.artworkURL ?: "")
                }
            }
        }
        return JSONObject().put("version", 1).put("values", values).toString()
    }

    /** Validate the entire payload before writing any keys to SharedPreferences. */
    fun restore(context: Context, content: String): Boolean {
        require(content.length <= 4 * 1024 * 1024) { "Playback state too large" }
        val root = JSONObject(content)
        require(root.getInt("version") == 1) { "Unsupported playback state version" }
        val data = root.getJSONObject("values")
        val allowed = (textKeys + intKeys + longKeys + booleanKeys).toSet()
        val parsed = LinkedHashMap<String, Any>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key in allowed) { "Unknown playback state key" }
            val value = data.get(key)
            parsed[key] = when (key) {
                in textKeys -> {
                    require(value is String && value.length <= 1024 * 1024) { "Invalid playback text" }
                    value
                }
                in intKeys -> {
                    require(value is Int && value >= 0) { "Invalid playlist index" }
                    value
                }
                in longKeys -> {
                    require(value is Number && value.toLong() >= 0) { "Invalid playback time" }
                    value.toLong()
                }
                else -> {
                    require(value is Boolean) { "Invalid playback flag" }
                    value
                }
            }
        }
        if (parsed.isEmpty()) return false
        Settings.getInstance(context).edit {
            parsed.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
            putBoolean(RESTORE_PENDING, true)
        }
        return true
    }
}
