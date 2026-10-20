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

internal fun sharedBackupActionsVisible(packageName: String): Boolean =
    packageName in setOf(
        "org.videolan.vlc.tvfix",
        "org.videolan.vlc.internalstorage",
        "org.videolan.vlc.smb"
    )

/**
 * One shared implementation for user-triggered and scheduled FULL backups.
 * Runs only after storage permission was previously granted; never requests UI
 * permissions from a background worker. One in-process lock prevents concurrent
 * jobs from overwriting each other's destination.
 */
object FullBackupEngine {
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

    suspend fun create(context: Context, automatic: Boolean, force: Boolean = false, allowReduction: Boolean = false): File = lock.withLock {
        val app = context.applicationContext
        if (automatic && !force && !AutoFullBackup.isDue(app)) throw FullBackupDeferred("Not due")
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
                val legacyTvBackup = if (app.packageName == "org.videolan.vlc.tvfix")
                    stageLegacyTvBackup(File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY))
                else null
                val dataRoot = app.getDir("db", Context.MODE_PRIVATE).parentFile
                    ?: throw IOException("App database root missing")
                val mediaDbDir = app.getDir("db", Context.MODE_PRIVATE)
                val appDbDir = File(dataRoot, "databases")
                val keyStoreDir = File(dataRoot, "app_keystore")
                val external = app.getExternalFilesDir(null)
                val artworkDir = external?.let { File(it, Medialibrary.MEDIALIB_FOLDER_NAME.removePrefix("/")) }
                val subtitlesDir = external?.let { File(it, "subtitles") }
                val hasArtwork = artworkDir?.isDirectory == true && containsBackupFiles(artworkDir)
                val hasSubtitles = subtitlesDir?.isDirectory == true && containsBackupFiles(subtitlesDir)
                val hasCredentials = keyStoreDir.isDirectory && containsBackupFiles(keyStoreDir)
                val temporary = createFullBackupStagingFile(target)
                staged = temporary
                ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                    val manifest = if (app.packageName == "org.videolan.vlc.tvfix")
                        """{"format":1,"package":"${app.packageName}","vlc":"3.7.2 Beta 2"}"""
                    else {
                        val variant = if (app.packageName == "org.videolan.vlc.smb") "smb" else "internal"
                        val components = buildList {
                            add("\"settings\"")
                            add("\"mediaDb\"")
                            add("\"appDb\"")
                            if (hasArtwork) add("\"artwork\"")
                            if (hasSubtitles) add("\"subtitles\"")
                            if (hasCredentials) add("\"credentials\"")
                        }.joinToString(",")
                        """{"format":2,"appFamily":"vlc-dual","variant":"$variant","package":"${app.packageName}","vlc":"3.7.2 Beta 2","components":[$components]}"""
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

                    listBackupChildren(mediaDbDir).forEach { file ->
                        if (file.name.startsWith(Medialibrary.VLC_MEDIA_DB_NAME.removePrefix("/")))
                            addFile(zip, file, "medialibrary/db/${file.name}")
                    }
                    listBackupChildren(appDbDir).forEach { addFile(zip, it, "appdb/${it.name}") }
                    artworkDir?.takeIf { hasArtwork }?.let { addDirectory(zip, it, "artwork/medialib") }
                    subtitlesDir?.takeIf { hasSubtitles }?.let { addDirectory(zip, it, "external/subtitles") }
                    if (hasCredentials) addDirectory(zip, keyStoreDir, "keystore")
                }
                // Preserve the last good archive unless structural and library checks pass.
                verifyProtectedSingleFullBackup(temporary, target, app.cacheDir, allowReduction)
                // The nested replacement is now durable; only now remove a legacy root copy.
                legacyTvBackup?.let {
                    if (it.exists() && !it.delete())
                        Log.w("FullBackup", "Could not remove the legacy root-level TV backup")
                }
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
        listBackupChildren(dir).forEach {
            if (it.isDirectory) addDirectory(zip, it, "$prefix/${it.name}")
            else addFile(zip, it, "$prefix/${it.name}")
        }
    }

    private fun containsBackupFiles(directory: File): Boolean =
        listBackupChildren(directory).any { it.isFile || it.isDirectory && containsBackupFiles(it) }

    private fun listBackupChildren(directory: File): Array<File> =
        directory.listFiles() ?: throw IOException("Cannot list backup directory: ${directory.name}")

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
