from pathlib import Path

TARGET = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


text = TARGET.read_text()

text = replace_once(text, '''            "full_restore" -> {
                showFullRestoreSelection()
                return true
            }''', '''            "full_restore" -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                }
                startActivityForResult(intent, 4242)
                return true
            }''', "file-first restore launcher")

text = replace_once(text, '''        if (data == null) return
        if (requestCode == FILE_PICKER_RESULT_CODE) {''', '''        if (data == null) return
        if (requestCode == 4242) {
            data.data?.let { showFullRestoreSelection(it) }
            return
        }
        if (requestCode == FILE_PICKER_RESULT_CODE) {''', "full backup picker result")

text = replace_once(text, '''    private fun fullBackupFile(): File =
        File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + "/VLC-Full-Backup.zip")''', '''    private fun dualVariantForPackage(packageName: String): String? = when (packageName) {
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
    }''', "variant backup filename")

text = replace_once(text, '''                        val manifest = """{"format":1,"package":"${context.packageName}","vlc":"3.7.2 Beta 2"}"""''', '''                        val sourceVariant = dualVariantForPackage(context.packageName)
                            ?: throw IOException("Unsupported VLC Dual package")
                        val manifest = """{"format":2,"appFamily":"vlc-dual","variant":"$sourceVariant","package":"${context.packageName}","vlc":"3.7.2 Beta 2","components":["settings","mediaDb","appDb","artwork","subtitles","credentials"]}"""''', "format 2 manifest")

text = replace_once(text, '''                    val manifestText = manifest.readText()
                    if (!manifestText.contains("\\\"format\\\":1") ||
                        !manifestText.contains("\\\"package\\\":\\\"${context.packageName}\\\""))
                        throw IOException("Unsupported or incompatible backup")''', '''                    val manifestText = manifest.readText()
                    validateDualManifest(manifestText)''', "dual-family manifest validation")

start = text.index("    private fun showFullRestoreSelection() {")
end = text.index("    private fun restoreFullBackup(", start)
new_selection = r'''    private data class BackupInspection(val sourcePackage: String, val components: Set<String>)

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
                    if (name.startsWith("/") || name.split('/').any { it == ".." })
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

'''
text = text[:start] + new_selection + text[end:]

text = replace_once(text, '''    private fun restoreFullBackup(
        restoreSettings: Boolean,''', '''    private fun restoreFullBackup(
        src: Uri,
        restoreSettings: Boolean,''', "restore selected URI signature")
text = replace_once(text, '''        val src = fullBackupFile()
        if (Medialibrary.getInstance().isWorking) {''', '''        if (Medialibrary.getInstance().isWorking) {''', "remove fixed restore source")
text = replace_once(text, '''                    ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zip ->''', '''                    val backupInput = context.contentResolver.openInputStream(src)
                        ?: throw IOException("Cannot open backup")
                    ZipInputStream(BufferedInputStream(backupInput)).use { zip ->''', "restore chosen URI")

# Enforce limits again during extraction; the selected document can change
# between the preview/inspection and the actual restore.
text = replace_once(text, '''                        var entry = zip.nextEntry
                        while (entry != null) {
                            val outFile = File(staging, entry.name)''', '''                        var entry = zip.nextEntry
                        var entryCount = 0
                        var totalExtracted = 0L
                        val maxExtracted = 4L * 1024 * 1024 * 1024
                        while (entry != null) {
                            entryCount++
                            if (entryCount > 100000) throw IOException("Backup contains too many entries")
                            val outFile = File(staging, entry.name)''', "restore entry count limit")
text = replace_once(text, '''                                FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                            }
                            zip.closeEntry()''', '''                                FileOutputStream(outFile).use { output ->
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
                            zip.closeEntry()''', "restore streamed size limit")


# Target identity is build/package identity, not portable preference data. Refuse to
# restore at all if the installed APK is not one of the two explicit Dual variants.
text = replace_once(text, '''                try {
                    staging.deleteRecursively()''', '''                try {
                    val targetVariant = dualVariantForPackage(context.packageName)
                        ?: throw IOException("Unsupported VLC Dual target package")
                    staging.deleteRecursively()''', "target variant guard")

# Directory components use the same stage/swap/rollback primitive as the databases.
text = replace_once(text, '''                    if (restoreArtwork) {
                        artworkDir?.let {
                            it.deleteRecursively()
                            copyDirectoryContents(File(staging, "artwork/medialib"), it)
                        }
                    }''', '''                    if (restoreArtwork) {
                        artworkDir?.let { replaceDirectoryContentsSafely(File(staging, "artwork/medialib"), it) }
                    }''', "transactional artwork restore")
text = replace_once(text, '''                    if (restoreSubtitles) {
                        subtitlesDir?.let {
                            val saved = File(staging, "external/subtitles")
                            if (saved.exists()) {
                                it.deleteRecursively()
                                copyDirectoryContents(saved, it)
                            }
                        }
                    }''', '''                    if (restoreSubtitles) {
                        subtitlesDir?.let { replaceDirectoryContentsSafely(File(staging, "external/subtitles"), it) }
                    }''', "transactional subtitle restore")
text = replace_once(text, '''                    if (restoreCredentials) {
                        val savedKeyStore = File(staging, "keystore")
                        if (savedKeyStore.exists()) {
                            keyStoreDir.deleteRecursively()
                            copyDirectoryContents(savedKeyStore, keyStoreDir)
                        }
                    }''', '''                    if (restoreCredentials) {
                        replaceDirectoryContentsSafely(File(staging, "keystore"), keyStoreDir)
                    }''', "transactional credential restore")

TARGET.write_text(text)
print(f"applied portable dual backup contract to {TARGET}")
