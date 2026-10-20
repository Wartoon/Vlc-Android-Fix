/*
 * *************************************************************************
 *  PreferencesAdvanced.java
 * **************************************************************************
 *  Copyright © 2015 VLC authors and VideoLAN
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
 *  ***************************************************************************
 */

package org.videolan.television.ui.preferences

import android.annotation.TargetApi
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.text.isDigitsOnly
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.AndroidDevices
import org.videolan.resources.EXPORT_SETTINGS_FILE
import org.videolan.resources.ROOM_DATABASE
import org.videolan.resources.SCHEME_PACKAGE
import org.videolan.resources.VLCInstance
import org.videolan.tools.BitmapCache
import org.videolan.tools.DAV1D_THREAD_NUMBER
import org.videolan.tools.KEY_AOUT
import org.videolan.tools.KEY_AUDIO_DIGITAL_OUTPUT
import org.videolan.tools.KEY_AUDIO_LAST_PLAYLIST
import org.videolan.tools.KEY_CURRENT_AUDIO
import org.videolan.tools.KEY_CURRENT_AUDIO_RESUME_ARTIST
import org.videolan.tools.KEY_CURRENT_AUDIO_RESUME_THUMB
import org.videolan.tools.KEY_CURRENT_AUDIO_RESUME_TITLE
import org.videolan.tools.KEY_CURRENT_MEDIA
import org.videolan.tools.KEY_CURRENT_MEDIA_RESUME
import org.videolan.tools.KEY_CUSTOM_LIBVLC_OPTIONS
import org.videolan.tools.KEY_DEBLOCKING
import org.videolan.tools.KEY_ENABLE_FRAME_SKIP
import org.videolan.tools.KEY_ENABLE_TIME_STRETCHING_AUDIO
import org.videolan.tools.KEY_ENABLE_VERBOSE_MODE
import org.videolan.tools.KEY_MEDIA_LAST_PLAYLIST
import org.videolan.tools.KEY_MEDIA_LAST_PLAYLIST_RESUME
import org.videolan.tools.KEY_NETWORK_CACHING_VALUE
import org.videolan.tools.KEY_OPENGL
import org.videolan.tools.KEY_PREFER_SMBV1
import org.videolan.tools.KEY_SHOW_UPDATE
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.MediaParsingService
import org.videolan.vlc.R
import org.videolan.vlc.gui.DebugLogActivity
import org.videolan.vlc.gui.preferences.FullBackupEngine
import org.videolan.vlc.gui.preferences.FullBackupAppStateSnapshot
import org.videolan.vlc.gui.preferences.FullBackupRestoreTransaction
import org.videolan.vlc.gui.preferences.AutoFullBackup
import org.videolan.vlc.gui.preferences.pendingFullBackupReduction
import org.videolan.vlc.gui.preferences.PlaybackStateBackup
import org.videolan.vlc.gui.preferences.validateRestoreDatabase
import org.videolan.vlc.gui.preferences.validateRestoreDatabaseDirectory
import org.videolan.vlc.gui.preferences.readFullBackupManifest
import org.videolan.vlc.gui.preferences.extractAppDatabaseBackup
import org.videolan.vlc.gui.browser.EXTRA_MRL
import org.videolan.vlc.gui.browser.FilePickerActivity
import org.videolan.vlc.gui.browser.KEY_PICKER_TYPE
import org.videolan.vlc.gui.dialogs.ConfirmDeleteDialog
import org.videolan.vlc.gui.dialogs.NEW_INSTALL
import org.videolan.vlc.gui.dialogs.RenameDialog
import org.videolan.vlc.gui.dialogs.UPDATE_DATE
import org.videolan.vlc.gui.dialogs.UPDATE_URL
import org.videolan.vlc.gui.dialogs.UpdateDialog
import org.videolan.vlc.gui.helpers.MedialibraryUtils
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.hf.StoragePermissionsDelegate.Companion.getWritePermission
import org.videolan.vlc.gui.helpers.restartMediaPlayer
import org.videolan.vlc.gui.preferences.search.PreferenceParser
import org.videolan.vlc.isVLC4
import org.videolan.vlc.providers.PickerType
import org.videolan.vlc.util.AutoUpdate
import org.videolan.vlc.util.FileUtils
import org.videolan.vlc.util.deleteAllWatchNext
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.BufferedOutputStream
import java.io.BufferedInputStream
import java.io.IOException

private const val FILE_PICKER_RESULT_CODE = 10000
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
class PreferencesAdvanced : BasePreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener, CoroutineScope by MainScope() {
    override fun getXml(): Int {
        return R.xml.preferences_adv
    }


    override fun getTitleId(): Int {
        return R.string.advanced_prefs_category
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val aoutPref = findPreference<ListPreference>(KEY_AOUT)
        if (isVLC4() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            aoutPref?.entryValues = activity.resources.getStringArray(R.array.aouts_complete_values)
            aoutPref?.entries = activity.resources.getStringArray(R.array.aouts_complete)
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        val f = super.buildPreferenceDialogFragment(preference)
        if (f is CustomEditTextPreferenceDialogFragment) {
            when (preference.key) {
                "network_caching" -> {
                    f.setInputType(InputType.TYPE_CLASS_NUMBER)
                    f.setFilters(arrayOf(InputFilter.LengthFilter(5)))
                }
            }
            return
        }
        if (!BuildConfig.DEBUG) findPreference<Preference>(KEY_SHOW_UPDATE)?.isVisible  = false
        super.onDisplayPreferenceDialog(preference)
    }

    override fun onStart() {
        super.onStart()
        preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        super.onStop()
        preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val ctx = activity
        if (preference.key == null || ctx == null) return false
        when (preference.key) {
            "debug_logs" -> {
                val intent = Intent(ctx, DebugLogActivity::class.java)
                startActivity(intent)
                return true
            }
            "nightly_install" -> {

                val appCompatActivity = activity as PreferencesActivity
                android.app.AlertDialog.Builder(appCompatActivity)
                        .setTitle(resources.getString(R.string.install_nightly))
                        .setMessage(resources.getString(R.string.install_nightly_alert))
                        .setPositiveButton(R.string.ok){ _, _ ->
                            appCompatActivity.lifecycleScope.launch {
                                AutoUpdate.checkUpdate(appCompatActivity.application, true) {url, date ->
                                    val updateDialog = UpdateDialog().apply {
                                        arguments = bundleOf(UPDATE_URL to url, UPDATE_DATE to date.time, NEW_INSTALL to true)
                                    }
                                    updateDialog.show(appCompatActivity.supportFragmentManager, "fragment_update")
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                return true
            }

            "clear_history" -> {
                val dialog = ConfirmDeleteDialog.newInstance(title = getString(R.string.clear_playback_history), description = getString(R.string.clear_history_message), buttonText = getString(R.string.clear_history))
                dialog.show((activity as FragmentActivity).supportFragmentManager, RenameDialog::class.simpleName)
                dialog.setListener {
                    Medialibrary.getInstance().clearHistory(Medialibrary.HISTORY_TYPE_GLOBAL)
                    Settings.getInstance(activity).edit()
                            .remove(KEY_AUDIO_LAST_PLAYLIST)
                            .remove(KEY_MEDIA_LAST_PLAYLIST)
                            .remove(KEY_MEDIA_LAST_PLAYLIST_RESUME)
                            .remove(KEY_CURRENT_AUDIO)
                            .remove(KEY_CURRENT_MEDIA)
                            .remove(KEY_CURRENT_MEDIA_RESUME)
                            .remove(KEY_CURRENT_AUDIO_RESUME_TITLE)
                            .remove(KEY_CURRENT_AUDIO_RESUME_ARTIST)
                            .remove(KEY_CURRENT_AUDIO_RESUME_THUMB)
                            .apply()
                }
                return true
            }

            "clear_app_data" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    val dialog = ConfirmDeleteDialog.newInstance(title = getString(R.string.clear_app_data), description = getString(R.string.clear_app_data_message), buttonText = getString(R.string.clear))
                    dialog.show((activity as FragmentActivity).supportFragmentManager, RenameDialog::class.simpleName)
                    dialog.setListener { (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData() }
                } else {
                    val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    i.addCategory(Intent.CATEGORY_DEFAULT)
                    i.data = Uri.fromParts(SCHEME_PACKAGE, activity.applicationContext.packageName, null)
                    startActivity(i)
                }
                return true
            }

            "clear_media_db" -> {
                val medialibrary = Medialibrary.getInstance()
                if (medialibrary.isWorking) {
                    activity?.let {
                        Toast.makeText(
                                it,
                                R.string.settings_ml_block_scan,
                                Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    val roots = medialibrary.foldersList
                    val dialog = ConfirmDeleteDialog.newInstance(
                            title = getString(R.string.clear_media_db),
                            description = getString(R.string.clear_media_db_message),
                            buttonText = getString(R.string.clear)
                    )
                    dialog.show(
                            (activity as FragmentActivity).supportFragmentManager,
                            RenameDialog::class.simpleName
                    )
                    dialog.setListener {
                        launch {
                            activity.stopService(Intent(activity, MediaParsingService::class.java))
                            withContext((Dispatchers.IO)) {
                                medialibrary.clearDatabase(false)
                                deleteAllWatchNext(activity)
                                //delete thumbnails
                                try {
                                    activity.getExternalFilesDir(null)?.let {
                                        val files =
                                                File(it.absolutePath + Medialibrary.MEDIALIB_FOLDER_NAME).listFiles()
                                        files?.forEach { file ->
                                            if (file.isFile) FileUtils.deleteFile(file)
                                        }
                                    }
                                    BitmapCache.clear()
                                } catch (e: IOException) {
                                    Log.e(this::class.java.simpleName, e.message, e)
                                }
                            }
                            for (root in roots) {
                                MedialibraryUtils.addDir(
                                    root.removePrefix("file://"),
                                    activity
                                )
                            }
                        }
                    }
                }
                return true
            }

            "quit_app" -> {
                android.os.Process.killProcess(android.os.Process.myPid())
                return true
            }

            "dump_media_db" -> {
                if (Medialibrary.getInstance().isWorking)
                    activity?.let { Toast.makeText(it, R.string.settings_ml_block_scan, Toast.LENGTH_LONG).show() }
                else {
                    val dst = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + Medialibrary.VLC_MEDIA_DB_NAME)
                    launch {
                        if ((activity as FragmentActivity).getWritePermission(Uri.fromFile(dst))) {
                            val copied = withContext(Dispatchers.IO) {
                                val db = File(activity.getDir("db", Context.MODE_PRIVATE).toString() + Medialibrary.VLC_MEDIA_DB_NAME)

                                FileUtils.copyFile(db, dst)
                            }
                            Toast.makeText(activity, getString(if (copied) R.string.dump_db_succes else R.string.dump_db_failure), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                return true
            }

            "dump_app_db" -> {
                val dst = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + ROOM_DATABASE)
                launch {
                    if ((activity as FragmentActivity).getWritePermission(Uri.fromFile(dst))) {
                        val copied = withContext(Dispatchers.IO) {
                            val db = File((activity as FragmentActivity).getDir("db", Context.MODE_PRIVATE).parent!! + "/databases")

                            val files = db.listFiles()?.map { it.path }?.toTypedArray()

                            if (files == null) false else
                                FileUtils.zip(files, dst.path)

                        }
                        Toast.makeText(activity, getString(if (copied) R.string.dump_db_succes else R.string.dump_db_failure), Toast.LENGTH_LONG).show()
                    }
                }
                return true
            }
            "export_settings" -> {
                val dst = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + EXPORT_SETTINGS_FILE)
                launch(Dispatchers.IO) {
                    if ((activity as FragmentActivity).getWritePermission(Uri.fromFile(dst))) {
                        PreferenceParser.exportPreferences(activity!!, dst)
                    }
                }
                return true
            }
            "restore_settings" -> {
                val filePickerIntent = Intent(activity, FilePickerActivity::class.java)
                filePickerIntent.putExtra(KEY_PICKER_TYPE, PickerType.SETTINGS.ordinal)
                startActivityForResult(filePickerIntent, FILE_PICKER_RESULT_CODE)
                return true
            }
            "full_backup" -> {
                createFullBackup()
                return true
            }
            "full_restore" -> {
                showFullRestoreSelection()
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data == null) return
        if (requestCode == FILE_PICKER_RESULT_CODE) {
            if (data.hasExtra(EXTRA_MRL)) {
                launch {
                    try {

                        PreferenceParser.restoreSettings(
                        activity, data.getStringExtra(
                            EXTRA_MRL
                        )!!.toUri()
                        )
                        (activity as PreferencesActivity).setRestartApp()
                    } catch (e: Exception) {
                        Log.e("EqualizerSettings", "onActivityResult: ${e.message}", e)
                        Toast.makeText(activity, R.string.invalid_settings_file, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun fullBackupFile(): File =
        tvFullBackupFile(File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY))

    // Keep an older root-level TV backup available for restore.
    private fun backupFileForRestore(): File =
        fullBackupFile().takeIf { it.isFile }
            ?: File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY, "VLC-Full-Backup.zip")

    private fun createFullBackup() {
        val medialibrary = Medialibrary.getInstance()
        if (medialibrary.isWorking) {
            UiTools.snacker(activity, getString(R.string.settings_ml_block_scan))
            return
        }
        val dst = fullBackupFile()
        launch {
            if (!(activity as FragmentActivity).getWritePermission(Uri.fromFile(dst))) return@launch
            try {
                val app = activity.applicationContext
                val reduction = withContext(Dispatchers.IO) {
                    pendingFullBackupReduction(app, dst)
                }
                if (reduction == null) {
                    AutoFullBackup.requestNow(app)
                    Toast.makeText(activity, R.string.full_backup_now_queued, Toast.LENGTH_LONG).show()
                } else {
                    android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.full_backup_reduction_title)
                        .setMessage(getString(R.string.full_backup_reduction_details, reduction))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.full_backup_reduction_accept) { _, _ ->
                            AutoFullBackup.requestNow(app, acceptReducedLibrary = true)
                            Toast.makeText(activity, R.string.full_backup_now_queued, Toast.LENGTH_LONG).show()
                        }
                        .show()
                }
            } catch (e: Exception) {
                Log.e("FullBackup", "Could not inspect or request complete backup", e)
                Toast.makeText(activity, R.string.full_backup_failure, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addDirectoryToBackup(zip: ZipOutputStream, directory: File, prefix: String) {
        directory.listFiles()?.forEach { child ->
            val entryName = "$prefix/${child.name}"
            if (child.isDirectory) addDirectoryToBackup(zip, child, entryName)
            else addToBackup(zip, child, entryName)
        }
    }

    private fun addToBackup(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun showFullRestoreSelection() {
        val src = backupFileForRestore()
        if (!src.isFile) {
            Toast.makeText(activity, getString(R.string.full_restore_missing, src.name), Toast.LENGTH_LONG).show()
            return
        }
        val labels = arrayOf(
            getString(R.string.full_restore_item_settings),
            getString(R.string.full_restore_item_media_db),
            getString(R.string.full_restore_item_app_db),
            getString(R.string.full_restore_item_artwork),
            getString(R.string.full_restore_item_subtitles),
            getString(R.string.full_restore_item_credentials)
        )
        val selected = booleanArrayOf(true, true, true, true, true, false)
        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.full_restore_choose)
            .setMultiChoiceItems(labels, selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton(R.string.full_restore_start) { _, _ ->
                if (selected.none { it }) {
                    Toast.makeText(activity, R.string.full_restore_nothing_selected, Toast.LENGTH_LONG).show()
                } else {
                    restoreFullBackup(
                        restoreSettings = selected[0],
                        restoreMediaDb = selected[1],
                        restoreAppDb = selected[2],
                        restoreArtwork = selected[3],
                        restoreSubtitles = selected[4],
                        restoreCredentials = selected[5]
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restoreFullBackup(
        restoreSettings: Boolean,
        restoreMediaDb: Boolean,
        restoreAppDb: Boolean,
        restoreArtwork: Boolean,
        restoreSubtitles: Boolean,
        restoreCredentials: Boolean
    ) {
        val src = backupFileForRestore()
        if (Medialibrary.getInstance().isWorking) {
            UiTools.snacker(activity, getString(R.string.settings_ml_block_scan))
            return
        }
        launch {
            val success = FullBackupEngine.withRestoreLock { withContext(Dispatchers.IO) {
                val context = activity
                val staging = File(context.cacheDir, "vlc_full_restore")
                try {
                    staging.deleteRecursively()
                    staging.mkdirs()

                    ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zip ->
                        var entry = zip.nextEntry
                        var entryCount = 0
                        var totalExtracted = 0L
                        val extractedNames = HashSet<String>()
                        val maxExtracted = 4L * 1024 * 1024 * 1024
                        while (entry != null) {
                            entryCount++
                            if (entryCount > 100000) throw IOException("Backup contains too many entries")
                            val normalizedName = entry.name.trimEnd('/')
                            if (entry.name.startsWith("/") || entry.name.contains(92.toChar()) ||
                                normalizedName.isEmpty() ||
                                normalizedName.split('/').any { it.isEmpty() || it == "." || it == ".." })
                                throw IOException("Invalid backup entry path")
                            if (!extractedNames.add(normalizedName)) throw IOException("Duplicate backup entry")
                            val outFile = File(staging, entry.name)
                            val safeRoot = staging.canonicalPath + File.separator
                            if (!outFile.canonicalPath.startsWith(safeRoot))
                                throw IOException("Invalid backup entry")
                            if (entry.isDirectory) {
                                if (!outFile.mkdirs() && !outFile.isDirectory)
                                    throw IOException("Cannot create restore directory")
                            }
                            else {
                                if (outFile.exists()) throw IOException("Duplicate backup entry")
                                val parent = outFile.parentFile ?: throw IOException("Invalid backup entry path")
                                if (!parent.exists() && !parent.mkdirs())
                                    throw IOException("Cannot create restore directory")
                                if (!parent.isDirectory) throw IOException("Invalid restore directory")
                                FileOutputStream(outFile).use { output ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        totalExtracted += count.toLong()
                                        if (totalExtracted > maxExtracted)
                                            throw IOException("Backup exceeds 4 GiB uncompressed")
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }

                    val manifest = File(staging, "manifest.json")
                    if (!manifest.isFile) throw IOException("Backup manifest missing")
                    val manifestText = readFullBackupManifest(manifest)
                    val manifestJson = try {
                        org.json.JSONObject(manifestText)
                    } catch (e: org.json.JSONException) {
                        throw IOException("Invalid backup manifest", e)
                    }
                    if (manifestJson.optInt("format", -1) != 1 ||
                        manifestJson.optString("package") != context.packageName)
                        throw IOException("Unsupported or incompatible backup")

                    val settingsFile = File(staging, "settings/settings.json")
                    val mediaDb = File(staging, "medialibrary/db/${Medialibrary.VLC_MEDIA_DB_NAME.removePrefix("/")}")
                    if (restoreSettings && !settingsFile.isFile) throw IOException("Settings missing")
                    if (restoreMediaDb && (!mediaDb.isFile || mediaDb.length() == 0L)) throw IOException("Media database missing")
                    val savedAppDb = File(staging, "appdb")
                    if (restoreAppDb && (savedAppDb.listFiles()?.none { it.isFile && it.length() > 0L } != false))
                        throw IOException("App database missing")
                    if (restoreMediaDb) validateRestoreDatabase(mediaDb)
                    if (restoreAppDb) validateRestoreDatabaseDirectory(savedAppDb)

                    val dataRoot = context.getDir("db", Context.MODE_PRIVATE).parentFile
                        ?: throw IOException("No app data directory")
                    val mediaDbDir = context.getDir("db", Context.MODE_PRIVATE)
                    val appDbDir = File(dataRoot, "databases")
                    val keyStoreDir = File(dataRoot, "app_keystore")
                    val external = context.getExternalFilesDir(null)
                    val artworkDir = external?.let { File(it, Medialibrary.MEDIALIB_FOLDER_NAME.removePrefix("/")) }
                    val subtitlesDir = external?.let { File(it, "subtitles") }

                    val restoreTargets = mutableListOf<Pair<File, File>>()
                    if (restoreMediaDb) restoreTargets += File(staging, "medialibrary/db") to mediaDbDir
                    if (restoreAppDb) restoreTargets += savedAppDb to appDbDir
                    if (restoreArtwork) {
                        artworkDir?.let {
                            val savedArtwork = File(staging, "artwork/medialib")
                            if (savedArtwork.isDirectory) restoreTargets += savedArtwork to it
                        }
                    }

                    if (restoreSubtitles) {
                        subtitlesDir?.let {
                            val saved = File(staging, "external/subtitles")
                            if (saved.isDirectory) restoreTargets += saved to it
                        }
                    }

                    if (restoreCredentials) {
                        val savedKeyStore = File(staging, "keystore")
                        if (savedKeyStore.isDirectory) restoreTargets += savedKeyStore to keyStoreDir
                    }

                    val appStateBeforeRestore = FullBackupAppStateSnapshot.capture(context)
                    FullBackupRestoreTransaction.replace(
                        targets = restoreTargets,
                        afterInstall = {
                            if (restoreSettings)
                                PreferenceParser.restoreSettings(activity, Uri.fromFile(settingsFile))
                            // Older TV archives have no playback entry; they remain restorable.
                            if (restoreSettings) {
                                val playbackFile = File(staging, PlaybackStateBackup.ENTRY)
                                if (playbackFile.isFile)
                                    PlaybackStateBackup.restore(context, playbackFile.readText(Charsets.UTF_8))
                            }
                            AutoFullBackup.deferAfterRestore(context)
                        },
                        rollbackAfterInstall = { appStateBeforeRestore.restore(context, restoreEqualizers = !restoreAppDb) }
                    )

                    staging.deleteRecursively()
                    true
                } catch (e: Exception) {
                    Log.e("FullBackup", "Full restore failed", e)
                    staging.deleteRecursively()
                    false
                }
            } }
            if (success) {
                Toast.makeText(activity, getString(R.string.full_restore_success), Toast.LENGTH_LONG).show()
                activity.window.decorView.postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 1500L)
            } else {
                Toast.makeText(activity, getString(R.string.full_restore_failure), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyDirectoryContents(source: File, destination: File) {
        if (!source.isDirectory) throw IOException("Restore source directory missing")
        if (!destination.exists() && !destination.mkdirs())
            throw IOException("Cannot create restore directory")
        source.listFiles()?.forEach { child ->
            val target = File(destination, child.name)
            if (child.isDirectory) copyDirectoryContents(child, target)
            else {
                target.parentFile?.mkdirs()
                FileInputStream(child).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
        }
    }


    private fun replaceDirectoryContentsSafely(source: File, destination: File) {
        val children = source.listFiles()
        if (!source.isDirectory || children.isNullOrEmpty())
            throw IOException("Restore source directory is missing or empty")
        val parent = destination.parentFile ?: throw IOException("Restore destination has no parent")
        val staged = File(parent, destination.name + ".restore-new")
        val previous = File(parent, destination.name + ".restore-old")
        staged.deleteRecursively()
        // An interrupted restore may have left the only recoverable copy here.
        if (previous.exists()) throw IOException("Previous restore backup still exists")
        copyDirectoryContents(source, staged)
        var movedOld = false
        try {
            if (destination.exists()) {
                if (!destination.renameTo(previous)) throw IOException("Cannot stage existing database")
                movedOld = true
            }
            if (!staged.renameTo(destination)) throw IOException("Cannot install restored database")
            previous.deleteRecursively()
        } catch (e: Exception) {
            if (destination.exists() && movedOld) destination.deleteRecursively()
            if (movedOld && previous.exists()) previous.renameTo(destination)
            staged.deleteRecursively()
            throw e
        }
    }

    private fun replaceDatabaseFileSafely(source: File, destination: File) {
        if (!source.isFile || source.length() == 0L) throw IOException("Restore database is missing or empty")
        val parent = destination.parentFile ?: throw IOException("Restore destination has no parent")
        val staged = File(parent, destination.name + ".restore-new")
        val liveFiles = listOf(destination, File(parent, destination.name + "-wal"), File(parent, destination.name + "-shm"), File(parent, destination.name + "-journal"))
        val backups = mutableListOf<Pair<File, File>>()
        val restoreBackups = liveFiles.map { File(parent, it.name + ".restore-old") }
        if (restoreBackups.any { it.exists() }) throw IOException("Previous restore backup still exists")
        if (staged.exists() && !staged.delete()) throw IOException("Cannot clear staged database")
        if (!FileUtils.copyFile(source, staged)) throw IOException("Cannot stage restored database")
        try {
            liveFiles.filter { it.exists() }.forEach { live ->
                val backup = File(parent, live.name + ".restore-old")
                if (!live.renameTo(backup)) throw IOException("Cannot stage existing database")
                backups += live to backup
            }
            if (!staged.renameTo(destination)) throw IOException("Cannot install restored database")
            backups.forEach { (_, backup) -> backup.delete() }
        } catch (e: Exception) {
            if (destination.exists()) destination.delete()
            backups.asReversed().forEach { (live, backup) -> if (backup.exists()) backup.renameTo(live) }
            staged.delete()
            throw e
        }
    }

    private fun restoreMediaDatabase() {
        val src = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + Medialibrary.VLC_MEDIA_DB_NAME)
        if (!src.isFile) {
            Toast.makeText(activity, getString(R.string.restore_media_db_missing), Toast.LENGTH_LONG).show()
            return
        }
        launch {
            val restored = withContext(Dispatchers.IO) {
                try {
                    val db = File(activity.getDir("db", Context.MODE_PRIVATE).toString() + Medialibrary.VLC_MEDIA_DB_NAME)
                    validateRestoreDatabase(src)
                    replaceDatabaseFileSafely(src, db)
                    true
                } catch (e: Exception) {
                    Log.e("DatabaseRestore", "Media database restore failed", e)
                    false
                }
            }
            if (restored) {
                Toast.makeText(activity, getString(R.string.restore_db_success_restart), Toast.LENGTH_LONG).show()
                activity.window.decorView.postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 1200L)
            } else {
                Toast.makeText(activity, getString(R.string.restore_db_failure), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restoreAppDatabase() {
        val src = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + ROOM_DATABASE)
        if (!src.isFile) {
            Toast.makeText(activity, getString(R.string.restore_app_db_missing), Toast.LENGTH_LONG).show()
            return
        }
        launch {
            val restored = withContext(Dispatchers.IO) {
                try {
                    val dbDir = File(activity.getDir("db", Context.MODE_PRIVATE).parent!!, "databases")
                    val stagedAppDb = File(activity.cacheDir, "vlc_appdb_restore")
                    stagedAppDb.deleteRecursively()
                    if (!stagedAppDb.mkdirs()) return@withContext false
                    extractAppDatabaseBackup(src, stagedAppDb)
                    replaceDirectoryContentsSafely(stagedAppDb, dbDir)
                    stagedAppDb.deleteRecursively()
                    true
                } catch (e: Exception) {
                    Log.e("DatabaseRestore", "App database restore failed", e)
                    false
                }
            }
            if (restored) {
                Toast.makeText(activity, getString(R.string.restore_db_success_restart), Toast.LENGTH_LONG).show()
                activity.window.decorView.postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 1200L)
            } else {
                Toast.makeText(activity, getString(R.string.restore_db_failure), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null || key == null) return
        when (key) {
            KEY_AOUT -> {
                launch { restartLibVLC() }
                Settings.getInstance(activity).let {
                    if (it.getString(KEY_AOUT, "0") == "2") it.putSingle(KEY_AUDIO_DIGITAL_OUTPUT, false)
                }
            }
            "network_caching" -> {
                sharedPreferences.edit {
                    try {
                        val origValue = Integer.parseInt(sharedPreferences.getString(key, "0"))
                        val newValue = origValue.coerceIn(0, 60000)
                        putInt(KEY_NETWORK_CACHING_VALUE, newValue)
                        findPreference<EditTextPreference>(key)?.let { it.text = newValue.toString() }
                        if (origValue != newValue) activity?.let { Toast.makeText(it, R.string.network_caching_popup, Toast.LENGTH_SHORT).show() }
                    } catch (e: NumberFormatException) {
                        putInt(KEY_NETWORK_CACHING_VALUE, 0)
                        findPreference<EditTextPreference>(key)?.let { it.text = "0" }
                        activity?.let { Toast.makeText(it, R.string.network_caching_popup, Toast.LENGTH_SHORT).show() }
                    }
                }
                launch { restartLibVLC() }
            }

            KEY_CUSTOM_LIBVLC_OPTIONS -> {
                launch {
                    try {
                        VLCInstance.restart()
                    } catch (e: IllegalStateException) {
                        activity?.let { Toast.makeText(it, R.string.custom_libvlc_options_invalid, Toast.LENGTH_LONG).show() }
                        sharedPreferences.putSingle(KEY_CUSTOM_LIBVLC_OPTIONS, "")
                    } finally {
                        restartMediaPlayer()
                    }
                    restartLibVLC()
                }
            }

            DAV1D_THREAD_NUMBER -> {
                val threadNumber = sharedPreferences.getString(key, "") ?: ""
                if (threadNumber != "" ) {
                    if ((threadNumber.isDigitsOnly() && threadNumber.toInt() < 1) || !threadNumber.isDigitsOnly()) {
                        UiTools.snacker(activity, R.string.dav1d_thread_number_invalid)
                        sharedPreferences.putSingle(DAV1D_THREAD_NUMBER, "")
                    }
                } else {
                    // In case of failure, after resetting the value to "" the SimpleSummaryProvider
                    // doesn't re-update it's summary to the default, has to be forced
                    val pref = findPreference<EditTextPreference>(key)
                    if (pref?.callChangeListener("") == true) {
                        pref.setText("");
                    }
                }
            }

            KEY_OPENGL, KEY_DEBLOCKING, KEY_ENABLE_FRAME_SKIP, KEY_ENABLE_TIME_STRETCHING_AUDIO, KEY_ENABLE_VERBOSE_MODE, KEY_PREFER_SMBV1 -> {
                launch { restartLibVLC() }
            }
        }
    }

    private suspend fun restartLibVLC() {
        VLCInstance.restart()
        restartMediaPlayer()
    }
}
