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

package org.videolan.vlc.gui.preferences

import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.text.isDigitsOnly
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
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
import org.videolan.tools.KEY_QUICK_PLAY
import org.videolan.tools.KEY_QUICK_PLAY_DEFAULT
import org.videolan.tools.POPUP_FORCE_LEGACY
import org.videolan.tools.RESULT_RESTART
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.R
import org.videolan.vlc.gui.DebugLogActivity
import org.videolan.vlc.gui.browser.EXTRA_MRL
import org.videolan.vlc.gui.browser.FilePickerActivity
import org.videolan.vlc.gui.browser.KEY_PICKER_TYPE
import org.videolan.vlc.gui.dialogs.CONFIRM_DELETE_DIALOG_RESULT
import org.videolan.vlc.gui.dialogs.CONFIRM_DELETE_DIALOG_RESULT_TYPE
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
import org.videolan.vlc.util.Permissions
import org.videolan.vlc.util.share
import java.io.File
import java.io.IOException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

private const val FILE_PICKER_RESULT_CODE = 10000
private const val RESULT_VALUE_CLEAR_HISTORY = 1
private const val RESULT_VALUE_CLEAR_MEDIA_DATABASE = 2
private const val RESULT_VALUE_CLEAR_APP_DATA = 3
class PreferencesAdvanced : BasePreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var needToRestartOnResume = false
    override fun getXml() =  R.xml.preferences_adv

    override fun getTitleId(): Int {
        return R.string.advanced_prefs_category
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        findPreference<EditTextPreference>("network_caching")?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
            it.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(5))
            it.setSelection(it.editableText.length)
        }

        val aoutPref = findPreference<ListPreference>(KEY_AOUT)
        if (isVLC4() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            aoutPref?.entryValues = requireActivity().resources.getStringArray(R.array.aouts_complete_values)
            aoutPref?.entries = requireActivity().resources.getStringArray(R.array.aouts_complete)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().supportFragmentManager.setFragmentResultListener(CONFIRM_DELETE_DIALOG_RESULT, viewLifecycleOwner) { requestKey, bundle ->
            val reason = bundle.getInt(CONFIRM_DELETE_DIALOG_RESULT_TYPE)
            when (reason) {
                RESULT_VALUE_CLEAR_HISTORY -> {
                    Medialibrary.getInstance().clearHistory(Medialibrary.HISTORY_TYPE_GLOBAL)
                    Settings.getInstance(requireActivity()).edit()
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
                RESULT_VALUE_CLEAR_MEDIA_DATABASE -> {
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
                        lifecycleScope.launch {
                            val roots = medialibrary.foldersList
                            withContext((Dispatchers.IO)) {
                                medialibrary.clearDatabase(false)
                                //delete thumbnails
                                try {
                                    requireActivity().getExternalFilesDir(null)?.let {
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
                                    requireContext()
                                )
                            }                        }
                    }
                }
                RESULT_VALUE_CLEAR_APP_DATA -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                        (requireActivity().getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        super.onStop()
        preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        if (needToRestartOnResume) {
            lifecycleScope.launch {
                VLCInstance.restart()
                UiTools.restartDialog(requireActivity())
            }
            needToRestartOnResume = false
        }
        super.onResume()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == null)
            return false
        when (preference.key) {
            "debug_logs" -> {
                val intent = Intent(requireContext(), DebugLogActivity::class.java)
                startActivity(intent)
                return true
            }
            "nightly_install" -> {

                android.app.AlertDialog.Builder(requireActivity())
                        .setTitle(resources.getString(R.string.install_nightly))
                        .setMessage(resources.getString(R.string.install_nightly_alert))
                        .setPositiveButton(R.string.ok){ _, _ ->
                            requireActivity().lifecycleScope.launch {
                                AutoUpdate.checkUpdate(requireActivity().application, true) {url, date ->
                                    val updateDialog = UpdateDialog().apply {
                                        arguments = bundleOf(UPDATE_URL to url, UPDATE_DATE to date.time, NEW_INSTALL to true)
                                    }
                                    updateDialog.show(requireActivity().supportFragmentManager, "fragment_update")
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                return true
            }
            "clear_history" -> {
                val dialog = ConfirmDeleteDialog.newInstance(title = getString(R.string.clear_playback_history), description = getString(R.string.clear_history_message), buttonText = getString(R.string.clear_history), resultType = RESULT_VALUE_CLEAR_HISTORY)
                dialog.show((activity as FragmentActivity).supportFragmentManager, RenameDialog::class.simpleName)
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
                    val dialog = ConfirmDeleteDialog.newInstance(
                        title = getString(R.string.clear_media_db),
                        description = getString(R.string.clear_media_db_message),
                        buttonText = getString(R.string.clear),
                        resultType = RESULT_VALUE_CLEAR_MEDIA_DATABASE
                    )
                    dialog.show(
                        requireActivity().supportFragmentManager,
                        RenameDialog::class.simpleName
                    )
                    return true
                }
            }
            "clear_app_data" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    val dialog = ConfirmDeleteDialog.newInstance(
                        title = getString(R.string.clear_app_data),
                        description = getString(R.string.clear_app_data_message),
                        buttonText = getString(R.string.clear),
                        resultType = RESULT_VALUE_CLEAR_APP_DATA
                    )
                    dialog.show(requireActivity().supportFragmentManager, RenameDialog::class.simpleName)
                } else {
                    val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    i.addCategory(Intent.CATEGORY_DEFAULT)
                    i.data = Uri.fromParts(SCHEME_PACKAGE, requireActivity().applicationContext.packageName, null)
                    startActivity(i)
                }
                return true
            }
            "quit_app" -> {
                android.os.Process.killProcess(android.os.Process.myPid())
                return true
            }
            "full_backup" -> {
                createFullBackup()
                return true
            }
            "full_restore" -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                }
                startActivityForResult(intent, 4242)
                return true
            }
            "optional_features" -> {
                loadFragment(PreferencesOptional())
                return true
            }
            KEY_QUICK_PLAY -> {
                val activity = activity
                activity?.setResult(RESULT_RESTART)
                if (!(preference as CheckBoxPreference).isChecked) {
                    findPreference<CheckBoxPreference>(KEY_QUICK_PLAY_DEFAULT)?.isChecked = false
                }
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data == null) return
        if (requestCode == 4242) {
            data.data?.let { showFullRestoreSelection(it) }
        }
    }

    private fun dualVariantForPackage(packageName: String): String? = when (packageName) {
        "org.videolan.vlc.internalstorage" -> "internal"
        "org.videolan.vlc.smb" -> "smb"
        else -> null
    }

    private fun fullBackupFile(): File {
        val name = when (dualVariantForPackage(requireContext().packageName)) {
            "internal" -> "VLC Complete Backup - Internal Storage.zip"
            "smb" -> "VLC Complete Backup - SMB.zip"
            else -> throw IllegalStateException("Unsupported VLC Dual package")
        }
        return File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + "/" + name)
    }

    private fun createFullBackup() {
        val medialibrary = Medialibrary.getInstance()
        if (medialibrary.isWorking) {
            UiTools.snacker(requireActivity(), getString(R.string.settings_ml_block_scan))
            return
        }
        val dst = fullBackupFile()
        lifecycleScope.launch {
            if (!getWritePermission(Uri.fromFile(dst))) return@launch
            val success = withContext(Dispatchers.IO) {
                try {
                    val context = requireContext()
                    val dataRoot = context.getDir("db", Context.MODE_PRIVATE).parentFile
                        ?: return@withContext false
                    val mediaDbDir = context.getDir("db", Context.MODE_PRIVATE)
                    val appDbDir = File(dataRoot, "databases")
                    val keyStoreDir = File(dataRoot, "app_keystore")
                    val external = context.getExternalFilesDir(null)
                    val artworkDir = external?.let { File(it, Medialibrary.MEDIALIB_FOLDER_NAME.removePrefix("/")) }
                    val subtitlesDir = external?.let { File(it, "subtitles") }

                    ZipOutputStream(BufferedOutputStream(FileOutputStream(dst))).use { zip ->
                        val sourceVariant = dualVariantForPackage(context.packageName)
                            ?: throw IOException("Unsupported VLC Dual package")
                        val manifest = """{"format":2,"appFamily":"vlc-dual","variant":"$sourceVariant","package":"${context.packageName}","vlc":"3.7.2 Beta 2","components":["settings","mediaDb","appDb","artwork","subtitles","credentials"]}"""
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(manifest.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()

                        val settings = PreferenceParser.getChangedPrefsJson(context)
                        zip.putNextEntry(ZipEntry("settings/settings.json"))
                        zip.write(settings.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()

                        mediaDbDir.listFiles()?.forEach { file ->
                            if (file.name.startsWith(Medialibrary.VLC_MEDIA_DB_NAME.removePrefix("/")))
                                addToBackup(zip, file, "medialibrary/db/${file.name}")
                        }
                        appDbDir.listFiles()?.forEach { file ->
                            addToBackup(zip, file, "appdb/${file.name}")
                        }
                        if (artworkDir?.exists() == true)
                            addDirectoryToBackup(zip, artworkDir, "artwork/medialib")
                        if (subtitlesDir?.exists() == true)
                            addDirectoryToBackup(zip, subtitlesDir, "external/subtitles")
                        if (keyStoreDir.exists())
                            addDirectoryToBackup(zip, keyStoreDir, "keystore")
                    }
                    true
                } catch (e: Exception) {
                    Log.e("FullBackup", "Full backup failed", e)
                    false
                }
            }
            if (success)
                UiTools.snackerConfirm(requireActivity(), getString(R.string.full_backup_success), confirmMessage = R.string.share, overAudioPlayer = false) {
                    requireActivity().share(dst)
                }
            else
                Toast.makeText(requireContext(), getString(R.string.full_backup_failure), Toast.LENGTH_LONG).show()
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

    private data class BackupInspection(val sourcePackage: String, val components: Set<String>)

    private fun validateDualManifest(manifestText: String): String {
        // Parse the manifest as JSON; substring matching can accept forged metadata.
        val manifest = try {
            org.json.JSONObject(manifestText)
        } catch (e: org.json.JSONException) {
            throw IOException("Invalid backup manifest", e)
        }
        val sourcePackage = manifest.optString("package")
        val sourceVariant = dualVariantForPackage(sourcePackage)
            ?: throw IOException("Backup package is not part of VLC Dual")
        if (manifest.optInt("format", -1) != 2 ||
            manifest.optString("appFamily") != "vlc-dual" ||
            manifest.optString("variant") != sourceVariant)
            throw IOException("Unsupported or incompatible backup")
        return sourcePackage
    }

    private fun inspectFullBackup(src: Uri): BackupInspection {
        var manifestText: String? = null
        val components = mutableSetOf<String>()
        requireContext().contentResolver.openInputStream(src)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                var entryCount = 0
                val inspectedNames = HashSet<String>()
                var totalDeclaredSize = 0L
                var totalActualSize = 0L
                val maxUncompressedSize = 4L * 1024 * 1024 * 1024
                while (entry != null) {
                    entryCount++
                    if (entryCount > 100000) throw IOException("Backup contains too many entries")
                    // ZIP headers may omit sizes; this is an early rejection only,
                    // not a substitute for bounded streaming during extraction.
                    if (entry.size > 0L) {
                        if (entry.size > 4L * 1024 * 1024 * 1024 ||
                            totalDeclaredSize > 4L * 1024 * 1024 * 1024 - entry.size)
                            throw IOException("Backup declares excessive uncompressed data")
                        totalDeclaredSize += entry.size
                    }
                    val name = entry.name
                    if (!inspectedNames.add(name.trimEnd('/')))
                        throw IOException("Duplicate backup entry")
                    if (name.startsWith("/") || name.contains(92.toChar()) ||
                        name.split('/').any { it.isEmpty() || it == "." || it == ".." })
                        throw IOException("Invalid backup entry")
                    when {
                        name == "manifest.json" -> {
                            if (manifestText != null) throw IOException("Duplicate backup manifest")
                            // Reject oversized manifests before allocating their full contents.
                            val manifestBytes = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            var total = 0
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                totalActualSize += count.toLong()
                                if (totalActualSize > maxUncompressedSize) throw IOException("Backup exceeds 4 GiB uncompressed")
                                if (total > 65536) throw IOException("Backup manifest exceeds 64 KiB")
                                manifestBytes.write(buffer, 0, count)
                            }
                            manifestText = manifestBytes.toString("UTF-8")
                        }
                        name == "settings/settings.json" -> components += "settings"
                        name.startsWith("medialibrary/db/") && !entry.isDirectory -> components += "mediaDb"
                        name.startsWith("appdb/") && !entry.isDirectory -> components += "appDb"
                        name.startsWith("artwork/medialib/") && !entry.isDirectory -> components += "artwork"
                        name.startsWith("external/subtitles/") && !entry.isDirectory -> components += "subtitles"
                        name.startsWith("keystore/") && !entry.isDirectory -> components += "credentials"
                    }
                    // Drain every entry with a running limit: ZIP size headers can be unknown.
                    val drainBuffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(drainBuffer)
                        if (count < 0) break
                        totalActualSize += count.toLong()
                        if (totalActualSize > maxUncompressedSize) throw IOException("Backup exceeds 4 GiB uncompressed")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IOException("Cannot open backup")
        val sourcePackage = validateDualManifest(manifestText ?: throw IOException("Backup manifest missing"))
        if (components.isEmpty()) throw IOException("Backup contains no restorable components")
        return BackupInspection(sourcePackage, components)
    }

    private fun showFullRestoreSelection(src: Uri) {
        lifecycleScope.launch {
            val inspection = withContext(Dispatchers.IO) {
                try { inspectFullBackup(src) } catch (e: Exception) {
                    Log.e("FullBackup", "Backup inspection failed", e)
                    null
                }
            }
            if (inspection == null) {
                Toast.makeText(requireContext(), getString(R.string.full_restore_failure), Toast.LENGTH_LONG).show()
                return@launch
            }
            val options = mutableListOf<Triple<String, String, Boolean>>()
            if ("settings" in inspection.components) options += Triple("settings", getString(R.string.full_restore_item_settings), true)
            if ("mediaDb" in inspection.components) options += Triple("mediaDb", getString(R.string.full_restore_item_media_db), true)
            if ("appDb" in inspection.components) options += Triple("appDb", getString(R.string.full_restore_item_app_db), true)
            if ("artwork" in inspection.components) options += Triple("artwork", getString(R.string.full_restore_item_artwork), true)
            if ("subtitles" in inspection.components) options += Triple("subtitles", getString(R.string.full_restore_item_subtitles), true)
            if ("credentials" in inspection.components && inspection.sourcePackage == requireContext().packageName)
                options += Triple("credentials", getString(R.string.full_restore_item_credentials), false)
            val selected = BooleanArray(options.size) { options[it].third }
            android.app.AlertDialog.Builder(requireActivity())
                .setTitle(R.string.full_restore_choose)
                .setMultiChoiceItems(options.map { it.second }.toTypedArray(), selected) { _, which, checked -> selected[which] = checked }
                .setPositiveButton(R.string.full_restore_start) { _, _ ->
                    val chosen = options.indices.filter { selected[it] }.map { options[it].first }.toSet()
                    if (chosen.isEmpty()) Toast.makeText(requireContext(), R.string.full_restore_nothing_selected, Toast.LENGTH_LONG).show()
                    else restoreFullBackup(src, "settings" in chosen, "mediaDb" in chosen, "appDb" in chosen, "artwork" in chosen, "subtitles" in chosen, "credentials" in chosen)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun restoreFullBackup(
        src: Uri,
        restoreSettings: Boolean,
        restoreMediaDb: Boolean,
        restoreAppDb: Boolean,
        restoreArtwork: Boolean,
        restoreSubtitles: Boolean,
        restoreCredentials: Boolean
    ) {
        if (Medialibrary.getInstance().isWorking) {
            UiTools.snacker(requireActivity(), getString(R.string.settings_ml_block_scan))
            return
        }
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                val context = requireContext()
                val staging = File(context.cacheDir, "vlc_full_restore")
                try {
                    val targetVariant = dualVariantForPackage(context.packageName)
                        ?: throw IOException("Unsupported VLC Dual target package")
                    staging.deleteRecursively()
                    staging.mkdirs()

                    val backupInput = context.contentResolver.openInputStream(src)
                        ?: throw IOException("Cannot open backup")
                    ZipInputStream(BufferedInputStream(backupInput)).use { zip ->
                        var entry = zip.nextEntry
                        var entryCount = 0
                        val extractedNames = HashSet<String>()
                        var totalExtracted = 0L
                        val maxExtracted = 4L * 1024 * 1024 * 1024
                        while (entry != null) {
                            entryCount++
                            if (entryCount > 100000) throw IOException("Backup contains too many entries")
                            val normalizedName = entry.name.trimEnd('/')
                            if (entry.name.startsWith("/") || entry.name.contains(92.toChar()) ||
                                normalizedName.isEmpty() ||
                                normalizedName.split('/').any { it.isEmpty() || it == "." || it == ".." })
                                throw IOException("Invalid backup entry path")
                            if (!extractedNames.add(normalizedName))
                                throw IOException("Duplicate backup entry")
                            val outFile = File(staging, entry.name)
                            val safeRoot = staging.canonicalPath + File.separator
                            if (!outFile.canonicalPath.startsWith(safeRoot))
                                throw IOException("Invalid backup entry")
                            if (entry.isDirectory) outFile.mkdirs()
                            else {
                                outFile.parentFile?.mkdirs()
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
                    val manifestText = manifest.readText()
                    val backupSourcePackage = validateDualManifest(manifestText)
                    if (restoreCredentials && backupSourcePackage != context.packageName)
                        throw IOException("Credentials cannot be restored across VLC Dual variants")

                    val settingsFile = File(staging, "settings/settings.json")
                    val mediaDb = File(staging, "medialibrary/db/${Medialibrary.VLC_MEDIA_DB_NAME.removePrefix("/")}")
                    if (restoreSettings && !settingsFile.isFile) throw IOException("Settings missing")
                    if (restoreMediaDb && (!mediaDb.isFile || mediaDb.length() == 0L)) throw IOException("Media database missing")
                    val savedAppDb = File(staging, "appdb")
                    if (restoreAppDb && (savedAppDb.listFiles()?.none { it.isFile && it.length() > 0L } != false))
                        throw IOException("App database missing")

                    if (restoreSettings)
                        PreferenceParser.restoreSettings(requireActivity(), Uri.fromFile(settingsFile))

                    val dataRoot = context.getDir("db", Context.MODE_PRIVATE).parentFile
                        ?: throw IOException("No app data directory")
                    val mediaDbDir = context.getDir("db", Context.MODE_PRIVATE)
                    val appDbDir = File(dataRoot, "databases")
                    val keyStoreDir = File(dataRoot, "app_keystore")
                    val external = context.getExternalFilesDir(null)
                    val artworkDir = external?.let { File(it, Medialibrary.MEDIALIB_FOLDER_NAME.removePrefix("/")) }
                    val subtitlesDir = external?.let { File(it, "subtitles") }

                    if (restoreMediaDb) replaceDirectoryContentsSafely(File(staging, "medialibrary/db"), mediaDbDir)

                    if (restoreAppDb) replaceDirectoryContentsSafely(savedAppDb, appDbDir)

                    if (restoreArtwork) {
                        artworkDir?.let { replaceDirectoryContentsSafely(File(staging, "artwork/medialib"), it) }
                    }

                    if (restoreSubtitles) {
                        subtitlesDir?.let { replaceDirectoryContentsSafely(File(staging, "external/subtitles"), it) }
                    }

                    if (restoreCredentials) {
                        replaceDirectoryContentsSafely(File(staging, "keystore"), keyStoreDir)
                    }

                    staging.deleteRecursively()
                    true
                } catch (e: Exception) {
                    Log.e("FullBackup", "Full restore failed", e)
                    staging.deleteRecursively()
                    false
                }
            }
            if (success) {
                Toast.makeText(requireContext(), getString(R.string.full_restore_success), Toast.LENGTH_LONG).show()
                requireActivity().window.decorView.postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 1500L)
            } else {
                Toast.makeText(requireContext(), getString(R.string.full_restore_failure), Toast.LENGTH_LONG).show()
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
        // Preserve an interrupted restore rollback instead of silently deleting it.
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
        if (staged.exists() && !staged.delete()) throw IOException("Cannot clear staged database")
        if (!FileUtils.copyFile(source, staged)) throw IOException("Cannot stage restored database")
        val liveFiles = listOf(destination, File(parent, destination.name + "-wal"), File(parent, destination.name + "-shm"), File(parent, destination.name + "-journal"))
        val backups = mutableListOf<Pair<File, File>>()
        try {
            liveFiles.filter { it.exists() }.forEach { live ->
                val backup = File(parent, live.name + ".restore-old")
                if (backup.exists() && !backup.delete()) throw IOException("Cannot clear old restore backup")
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

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null || key == null) return
        when (key) {
            KEY_AOUT -> {
                lifecycleScope.launch { restartLibVLC() }
                Settings.getInstance(requireActivity()).let {
                    if (it.getString(KEY_AOUT, "0") == "2") it.putSingle(KEY_AUDIO_DIGITAL_OUTPUT, false)
                }
            }
            "network_caching" -> {
                sharedPreferences.edit {
                    try {
                        val origValue = Integer.parseInt(sharedPreferences.getString(key, "0") ?: "0")
                        val newValue = origValue.coerceIn(0, 60000)
                        putInt(KEY_NETWORK_CACHING_VALUE, newValue)
                        findPreference<EditTextPreference>(key)?.let { it.text = newValue.toString() }
                        if (origValue != newValue) UiTools.snacker(requireActivity(), R.string.network_caching_popup)
                    } catch (e: NumberFormatException) {
                        putInt(KEY_NETWORK_CACHING_VALUE, 0)
                        findPreference<EditTextPreference>(key)?.let { it.text = "0" }
                        UiTools.snacker(requireActivity(), R.string.network_caching_popup)
                    }
                }
                lifecycleScope.launch { restartLibVLC() }
            }
            // No break because need VLCInstance.restart();
            KEY_CUSTOM_LIBVLC_OPTIONS -> {
                lifecycleScope.launch {
                    try {
                        VLCInstance.restart()
                    } catch (e: IllegalStateException) {
                        UiTools.snacker(requireActivity(), R.string.custom_libvlc_options_invalid)
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
                        UiTools.snacker(requireActivity(), R.string.dav1d_thread_number_invalid)
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
            KEY_OPENGL, KEY_DEBLOCKING, KEY_ENABLE_FRAME_SKIP, KEY_ENABLE_TIME_STRETCHING_AUDIO, KEY_ENABLE_VERBOSE_MODE -> {
                lifecycleScope.launch { restartLibVLC() }
            }
            KEY_PREFER_SMBV1 -> {
                lifecycleScope.launch { VLCInstance.restart() }
                UiTools.restartDialog(requireActivity())
            }
        }
    }

    private suspend fun restartLibVLC() {
        VLCInstance.restart()
        restartMediaPlayer()
    }
}
