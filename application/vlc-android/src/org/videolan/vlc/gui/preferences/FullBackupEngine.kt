package org.videolan.vlc.gui.preferences

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.AndroidDevices
import org.videolan.tools.Settings
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.gui.preferences.search.PreferenceParser
import org.videolan.vlc.util.Permissions
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class FullBackupDeferred(message: String) : IOException(message)

/**
 * One shared implementation for user-triggered and scheduled FULL backups.
 * Runs only after storage permission was previously granted; never requests UI
 * permissions from a background worker. One in-process lock prevents concurrent
 * jobs from overwriting each other's destination.
 */
internal object FullBackupEngine {
    private val lock = Mutex()

    suspend fun <T> withRestoreLock(action: suspend () -> T): T =
        lock.withLock { action() }

    fun destination(context: Context): File {
        val root = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY)
        return when (context.packageName) {
            "org.videolan.vlc.internalstorage", "org.videolan.vlc.smb" ->
                File(File(File(root, "VLC"),
                    if (context.packageName == "org.videolan.vlc.smb")
                        "VLC SMB App Backup" else "VLC Internal Storage App Backup"),
                    if (context.packageName == "org.videolan.vlc.smb")
                        "VLC Complete Backup - SMB.zip" else "VLC Complete Backup - Internal Storage.zip")
            "org.videolan.vlc.tvfix" -> tvFullBackupFile(root)
            else -> throw IOException("Unsupported VLC full-backup package")
        }
    }

    suspend fun create(context: Context, automatic: Boolean): File = lock.withLock {
        val app = context.applicationContext
        if (automatic && !AutoFullBackup.isDue(app)) throw FullBackupDeferred("Not due")
        if (Medialibrary.getInstance().isWorking) throw FullBackupDeferred("Media library busy")
        if (automatic && withContext(Dispatchers.Main) { PlaybackService.instance?.isPlaying == true })
            throw FullBackupDeferred("Playback active")
        if (automatic && !Permissions.canWriteStorage(app))
            throw IOException("Shared-storage permission is not granted")

        // PlaybackService is owned by the main thread. Capture before disk IO.
        val playback = withContext(Dispatchers.Main) { PlaybackStateBackup.capture(app) }
        val target = destination(app)
        withContext(Dispatchers.IO) {
            var staged: File? = null
            try {
                val dataRoot = app.getDir("db", Context.MODE_PRIVATE).parentFile
                    ?: throw IOException("App database root missing")
                val mediaDbDir = app.getDir("db", Context.MODE_PRIVATE)
                val appDbDir = File(dataRoot, "databases")
                val keyStoreDir = File(dataRoot, "app_keystore")
                val external = app.getExternalFilesDir(null)
                val artworkDir = external?.let { File(it, Medialibrary.MEDIALIB_FOLDER_NAME.removePrefix("/")) }
                val subtitlesDir = external?.let { File(it, "subtitles") }
                val temporary = createFullBackupStagingFile(target)
                staged = temporary
                ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                    val manifest = if (app.packageName == "org.videolan.vlc.tvfix")
                        """{"format":1,"package":"${app.packageName}","vlc":"3.7.2 Beta 2"}"""
                    else {
                        val variant = if (app.packageName == "org.videolan.vlc.smb") "smb" else "internal"
                        """{"format":2,"appFamily":"vlc-dual","variant":"$variant","package":"${app.packageName}","vlc":"3.7.2 Beta 2","components":["settings","mediaDb","appDb","artwork","subtitles","credentials"]}"""
                    }
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("settings/settings.json"))
                    zip.write(PreferenceParser.getChangedPrefsJson(app).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry(PlaybackStateBackup.ENTRY))
                    zip.write(playback.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    mediaDbDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith(Medialibrary.VLC_MEDIA_DB_NAME.removePrefix("/")))
                            addFile(zip, file, "medialibrary/db/${file.name}")
                    }
                    appDbDir.listFiles()?.forEach { addFile(zip, it, "appdb/${it.name}") }
                    if (artworkDir?.isDirectory == true)
                        addDirectory(zip, artworkDir, "artwork/medialib")
                    if (subtitlesDir?.isDirectory == true)
                        addDirectory(zip, subtitlesDir, "external/subtitles")
                    if (keyStoreDir.isDirectory) addDirectory(zip, keyStoreDir, "keystore")
                }
                // Both modes replace the SAME main file and share its 3 generations.
                verifyRotateAndReplaceFullBackup(temporary, target)
            } catch (e: Exception) {
                Log.e("FullBackup", "Full backup failed", e)
                throw e
            } finally {
                staged?.delete()
            }
        }
        AutoFullBackup.recordSuccess(app, automatic)
        target
    }

    private fun addDirectory(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.listFiles()?.forEach {
            if (it.isDirectory) addDirectory(zip, it, "$prefix/${it.name}")
            else addFile(zip, it, "$prefix/${it.name}")
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
