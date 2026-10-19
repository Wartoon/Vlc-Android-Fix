package org.videolan.vlc.gui.preferences

import android.content.Context
import org.videolan.tools.Settings
import org.videolan.vlc.repository.EqualizerRepository
import org.videolan.vlc.util.EqualizerUtil
import org.videolan.vlc.util.JsonUtil
import java.io.IOException

/** Captures app state changed by the settings and playback restore callbacks. */
internal class FullBackupAppStateSnapshot private constructor(
    private val preferences: Map<String, Any>,
    private val equalizersJson: String
) {
    suspend fun restore(context: Context, restoreEqualizers: Boolean = true) {
        val prefs = Settings.getInstance(context)
        val restored = prefs.edit().clear()
        preferences.forEach { (key, value) ->
            when (value) {
                is Boolean -> restored.putBoolean(key, value)
                is Int -> restored.putInt(key, value)
                is Long -> restored.putLong(key, value)
                is Float -> restored.putFloat(key, value)
                is String -> restored.putString(key, value)
                is Set<*> -> restored.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> throw IOException("Unsupported preference value for $key")
            }
        }
        if (!restored.commit()) throw IOException("Cannot roll back preferences")
        Settings.init(context.applicationContext)

        if (restoreEqualizers) {
            val repository = EqualizerRepository.getInstance(context)
            repository.getCustomEqualizers().forEach { repository.delete(it.equalizerEntry) }
            EqualizerUtil.importAll(context, JsonUtil.getEqualizersFromJson(equalizersJson), null)
        }
    }

    companion object {
        fun capture(context: Context): FullBackupAppStateSnapshot {
            val values = Settings.getInstance(context).all.mapValues { (_, value) ->
                requireNotNull(value) { "Cannot snapshot a null preference" }
                if (value is Set<*>) {
                    require(value.all { it is String }) { "Cannot snapshot a non-string preference set" }
                    value.toSet()
                } else value
            }
            return FullBackupAppStateSnapshot(values, EqualizerUtil.getEqualizerExportString(context))
        }
    }
}
