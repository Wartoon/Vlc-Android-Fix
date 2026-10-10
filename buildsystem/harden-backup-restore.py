from pathlib import Path

TARGETS = [
    Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt"),
]

HELPERS = r'''
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
'''

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def harden(path: Path) -> None:
    text = path.read_text()
    if "private fun replaceDirectoryContentsSafely(" in text:
        print(f"already hardened {path}")
        return

    text = replace_once(
        text,
        '                    val manifest = File(staging, "manifest.json")\n                    if (!manifest.isFile || !manifest.readText().contains("\\\"format\\\":1"))\n                        throw IOException("Unsupported backup format")',
        '                    val manifest = File(staging, "manifest.json")\n                    if (!manifest.isFile) throw IOException("Backup manifest missing")\n                    val manifestText = manifest.readText()\n                    if (!manifestText.contains("\\\"format\\\":1") ||\n                        !manifestText.contains("\\\"package\\\":\\\"${context.packageName}\\\""))\n                        throw IOException("Unsupported or incompatible backup")',
        "manifest validation",
    )
    text = replace_once(
        text,
        '                    if (restoreMediaDb && !mediaDb.isFile) throw IOException("Media database missing")',
        '                    if (restoreMediaDb && (!mediaDb.isFile || mediaDb.length() == 0L)) throw IOException("Media database missing")\n                    val savedAppDb = File(staging, "appdb")\n                    if (restoreAppDb && (savedAppDb.listFiles()?.none { it.isFile && it.length() > 0L } != false))\n                        throw IOException("App database missing")',
        "restore source validation",
    )
    old_media = '''                    if (restoreMediaDb) {
                        mediaDbDir.listFiles()?.filter {
                            it.name.startsWith(Medialibrary.VLC_MEDIA_DB_NAME.removePrefix("/"))
                        }?.forEach { if (!it.delete()) throw IOException("Cannot replace media database") }
                        copyDirectoryContents(File(staging, "medialibrary/db"), mediaDbDir)
                    }'''
    text = replace_once(text, old_media, '                    if (restoreMediaDb) replaceDirectoryContentsSafely(File(staging, "medialibrary/db"), mediaDbDir)', "media DB full restore")
    old_app = '''                    if (restoreAppDb) {
                        if (!appDbDir.exists()) appDbDir.mkdirs()
                        appDbDir.listFiles()?.forEach {
                            if (!it.deleteRecursively()) throw IOException("Cannot replace app database")
                        }
                        copyDirectoryContents(File(staging, "appdb"), appDbDir)
                    }'''
    text = replace_once(text, old_app, '                    if (restoreAppDb) replaceDirectoryContentsSafely(savedAppDb, appDbDir)', "app DB full restore")
    text = replace_once(text, '    private fun copyDirectoryContents(source: File, destination: File) {\n        if (!source.exists()) return', '    private fun copyDirectoryContents(source: File, destination: File) {\n        if (!source.isDirectory) throw IOException("Restore source directory missing")', "copy source validation")
    marker = '    private fun restoreMediaDatabase() {'
    text = replace_once(text, marker, HELPERS + '\n' + marker, "safe helper insertion")
    old_standalone_media = '''                    val parent = db.parentFile ?: return@withContext false
                    val names = arrayOf(db.name, db.name + "-wal", db.name + "-shm", db.name + "-journal")
                    names.forEach { name ->
                        val current = File(parent, name)
                        if (current.exists() && !current.delete()) return@withContext false
                    }
                    FileUtils.copyFile(src, db)'''
    text = replace_once(text, old_standalone_media, '                    replaceDatabaseFileSafely(src, db)\n                    true', "standalone media restore")
    old_standalone_app = '''                    if (!dbDir.exists()) dbDir.mkdirs()

                    dbDir.listFiles()?.forEach { current ->
                        if (!current.delete()) return@withContext false
                    }

                    ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zip ->'''
    new_standalone_app = '''                    val stagedAppDb = File(requireContext().cacheDir, "vlc_appdb_restore")
                    stagedAppDb.deleteRecursively()
                    if (!stagedAppDb.mkdirs()) return@withContext false

                    val extracted = ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zip ->'''
    text = replace_once(text, old_standalone_app, new_standalone_app, "standalone app staging")
    text = replace_once(text, '                                    val outFile = File(dbDir, safeName)', '                                    val outFile = File(stagedAppDb, safeName)', "standalone app extraction target")
    old_end = '''                        extracted
                    }
                } catch (e: Exception) {'''
    new_end = '''                        extracted
                    }
                    if (!extracted) throw IOException("App database backup is empty")
                    replaceDirectoryContentsSafely(stagedAppDb, dbDir)
                    stagedAppDb.deleteRecursively()
                    true
                } catch (e: Exception) {'''
    text = replace_once(text, old_end, new_end, "standalone app install")
    path.write_text(text)
    print(f"hardened {path}")

for target in TARGETS:
    if target.exists(): harden(target)
