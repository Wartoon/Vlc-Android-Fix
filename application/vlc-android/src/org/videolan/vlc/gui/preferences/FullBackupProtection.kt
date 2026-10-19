package org.videolan.vlc.gui.preferences

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import org.videolan.medialibrary.interfaces.Medialibrary
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** A valid ZIP can still contain a library that silently lost its media sources. */
internal class FullBackupLibraryReduced(message: String) : IOException(message)

data class FullBackupLibrarySnapshot(
    val media: Long,
    val folders: Long,
    val playlists: Long,
    val artwork: Long,
    val folderPaths: Set<String>
)

/** A decrease is treated as suspicious, not automatically as an intentional deletion. */
internal fun fullBackupReduction(
    previous: FullBackupLibrarySnapshot,
    candidate: FullBackupLibrarySnapshot
): String? {
    val problems = ArrayList<String>()
    if (candidate.media < previous.media) problems += "Media: ${previous.media} -> ${candidate.media}"
    if (candidate.folders < previous.folders) problems += "Folders: ${previous.folders} -> ${candidate.folders}"
    if (candidate.playlists < previous.playlists) problems += "Playlists: ${previous.playlists} -> ${candidate.playlists}"
    if (candidate.artwork < previous.artwork) problems += "Artwork: ${previous.artwork} -> ${candidate.artwork}"
    val missingSources = previous.folderPaths - candidate.folderPaths
    if (missingSources.isNotEmpty()) problems += "Missing media folders: ${missingSources.size}"
    return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/** Every complete archive must contain at least one non-empty SQLite app database. */
internal fun validateAppDbBackupEntries(entries: Collection<Pair<String, Long>>) {
    val hasDatabase = entries.any { (name, size) ->
        val fileName = name.substringAfterLast('/')
        name.startsWith("appdb/") && fileName.isNotEmpty() && size > 0L &&
            isRestoreDatabaseFileName(fileName)
    }
    if (!hasDatabase) throw IOException("App database missing from backup")
}

/**
 * Evaluates the live library before a user-triggered backup so the user can
 * explicitly accept a legitimately reduced library. Background backups never
 * use that override.
 */
internal fun pendingFullBackupReduction(context: Context, destination: File): String? {
    if (!destination.isFile) return null
    val previous = inspectFullBackupLibrary(destination, context.cacheDir, requirePlayback = false)
    val mediaDb = File(context.getDir("db", Context.MODE_PRIVATE),
        Medialibrary.VLC_MEDIA_DB_NAME.removePrefix("/"))
    if (!mediaDb.isFile) throw IOException("Current media database missing")
    val artwork = context.getExternalFilesDir(null)?.let {
        File(it, Medialibrary.MEDIALIB_FOLDER_NAME.removePrefix("/"))
    }
    return fullBackupReduction(previous, readFullBackupDatabase(mediaDb, countArtwork(artwork)))
}

/**
 * Both automatic and user-requested backups use one permanent ZIP. The old
 * primary remains untouched if structural or library-content checks fail.
 * Legacy Previous 1/2 files are removed only after a successful replacement.
 */
internal fun verifyProtectedSingleFullBackup(
    staged: File,
    destination: File,
    verificationDirectory: File,
    allowReduction: Boolean = false
) {
    val candidate = inspectFullBackupLibrary(staged, verificationDirectory, requirePlayback = true)
    if (destination.exists()) {
        if (!destination.isFile) throw IOException("Backup destination is not a file")
        val previous = inspectFullBackupLibrary(destination, verificationDirectory, requirePlayback = false)
        val reduction = fullBackupReduction(previous, candidate)
        if (reduction != null && !allowReduction)
            throw FullBackupLibraryReduced("Existing complete backup preserved: $reduction")
    }
    // Same-directory atomic replacement, with an additional ZIP validation.
    verifyAndReplaceFullBackup(staged, destination)
    // Never remove older generations until the single new ZIP is durable.
    val directory = destination.parentFile ?: return
    listOf(" - Previous 1.zip", " - Previous 2.zip").forEach { suffix ->
        val legacy = File(directory, destination.nameWithoutExtension + suffix)
        if (legacy.exists() && !legacy.delete())
            android.util.Log.w("FullBackup", "Could not remove an old backup generation")
    }
}

private fun countArtwork(directory: File?): Long {
    if (directory?.isDirectory != true) return 0L
    var count = 0L
    directory.walkTopDown().forEach { if (it.isFile) count++ }
    return count
}

/**
 * Read the copied database with a bundled SQLite engine so schema syntax does
 * not depend on the Android release's system SQLite version. Android 9 ships
 * SQLite 3.22, which cannot parse the MediaLibrary's UPDATE FROM trigger.
 */
private fun readFullBackupDatabase(file: File, artwork: Long): FullBackupLibrarySnapshot {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        return readWithBundledSqlite(file, artwork)
    }
    // sqlite-bundled supports API 21+. Keep the app installable below that API
    // level and retain the previous validation path for those legacy devices.
    val db = try {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    } catch (e: Exception) {
        throw IOException("Media database cannot be opened", e)
    }
    try {
        fun count(table: String): Long {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("Cannot count $table")
                return cursor.getLong(0)
            }
        }
        val paths = HashSet<String>()
        db.rawQuery("SELECT path FROM Folder WHERE path IS NOT NULL", null).use { cursor ->
            while (cursor.moveToNext()) paths += cursor.getString(0)
        }
        return FullBackupLibrarySnapshot(count("Media"), count("Folder"),
            count("Playlist"), artwork, paths)
    } catch (e: Exception) {
        throw IOException("Media library tables are not readable", e)
    } finally {
        db.close()
    }
}

private fun readWithBundledSqlite(file: File, artwork: Long): FullBackupLibrarySnapshot {
    return try {
        val reader = Class.forName("org.videolan.vlc.gui.preferences.BundledBackupDatabaseReader")
        reader.getMethod("read", File::class.java, java.lang.Long.TYPE)
            .invoke(null, file, artwork) as FullBackupLibrarySnapshot
    } catch (e: InvocationTargetException) {
        throw IOException("Media library tables are not readable", e.targetException)
    } catch (e: LinkageError) {
        throw IOException("Bundled SQLite validator is unavailable", e)
    } catch (e: Exception) {
        throw IOException("Bundled SQLite validator is unavailable", e)
    }
}

/** Verify CRC/size for every entry, required records, and the actual DB tables. */
private fun inspectFullBackupLibrary(
    archive: File, verificationDirectory: File, requirePlayback: Boolean
): FullBackupLibrarySnapshot {
    if (!verificationDirectory.isDirectory && !verificationDirectory.mkdirs())
        throw IOException("Backup validation directory unavailable")
    ZipFile(archive).use { zip ->
        val required = hashSetOf("manifest.json", "settings/settings.json")
        if (requirePlayback) required += PlaybackStateBackup.ENTRY
        val entries = HashSet<String>()
        val appDbEntries = ArrayList<Pair<String, Long>>()
        var artwork = 0L
        var uncompressed = 0L
        val buffer = ByteArray(65536)
        val iterator = zip.entries()
        while (iterator.hasMoreElements()) {
            val entry = iterator.nextElement()
            val name = entry.name
            if (!entries.add(name)) throw IOException("Duplicate backup ZIP entry")
            if (entries.size > 100000 || name.startsWith("/") ||
                name.split('/').any { it == ".." })
                throw IOException("Invalid backup ZIP entry")
            if (entry.isDirectory) continue
            val crc = CRC32()
            var length = 0L
            zip.getInputStream(entry).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                    length += read
                    if (length > 4L * 1024 * 1024 * 1024)
                        throw IOException("Backup ZIP entry exceeds safety limit")
                }
            }
            if (length != entry.size || crc.value != entry.crc)
                throw IOException("Backup ZIP entry corrupted")
            if (name.startsWith("appdb/")) appDbEntries += name to length
            if (name in required && length == 0L)
                throw IOException("Required backup entry is empty")
            if (name.startsWith("artwork/medialib/")) artwork++
            uncompressed += length
            if (uncompressed > 4L * 1024 * 1024 * 1024)
                throw IOException("Backup ZIP exceeds restore safety limit")
        }
        if (!entries.containsAll(required))
            throw IOException("Required backup entries missing")
        if (requirePlayback) validateAppDbBackupEntries(appDbEntries)
        val dbName = Medialibrary.VLC_MEDIA_DB_NAME.removePrefix("/")
        val dbEntryName = "medialibrary/db/$dbName"
        val mediaDb = zip.getEntry(dbEntryName)
            ?: throw IOException("Media database not found in backup")
        if (mediaDb.isDirectory || mediaDb.size <= 0L || mediaDb.size > 256L * 1024 * 1024)
            throw IOException("Media database has invalid size")

        // A live SQLite database may have committed rows only in its WAL. Reading
        // the extracted main .db alone silently reports a stale media count and
        // can approve an incomplete replacement. Read the archived WAL as well.
        // The -shm file is transient and SQLite recreates it in this private,
        // writable directory rather than trusting a copied live shared-memory file.
        val scratch = File.createTempFile("vlc-backup-check-", ".dir", verificationDirectory)
        if (!scratch.delete() || !scratch.mkdir())
            throw IOException("Cannot prepare isolated backup database validation")
        try {
            val extracted = File(scratch, dbName)
            zip.getInputStream(mediaDb).use { input ->
                extracted.outputStream().use { output -> input.copyTo(output) }
            }
            zip.getEntry("$dbEntryName-wal")?.let { wal ->
                if (wal.isDirectory || wal.size < 0L)
                    throw IOException("Invalid media database WAL entry")
                zip.getInputStream(wal).use { input ->
                    File(scratch, "$dbName-wal").outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            if (requirePlayback) {
                val appDbDirectory = File(scratch, "appdb")
                if (!appDbDirectory.mkdirs()) throw IOException("Cannot prepare app database validation")
                appDbEntries.forEach { (name, _) ->
                    val entry = zip.getEntry(name) ?: throw IOException("App database entry missing")
                    val relativeName = name.removePrefix("appdb/")
                    if (relativeName.isEmpty() || relativeName.split('/').any { it.isEmpty() || it == "." || it == ".." })
                        throw IOException("Invalid app database entry")
                    val outputFile = File(appDbDirectory, relativeName)
                    val safeRoot = appDbDirectory.canonicalPath + File.separator
                    if (!outputFile.canonicalPath.startsWith(safeRoot))
                        throw IOException("Invalid app database entry")
                    val parent = outputFile.parentFile ?: throw IOException("Invalid app database entry")
                    if (!parent.isDirectory && !parent.mkdirs())
                        throw IOException("Cannot create app database validation directory")
                    zip.getInputStream(entry).use { input ->
                        outputFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                validateRestoreDatabaseDirectory(appDbDirectory)
            }
            return readFullBackupDatabase(extracted, artwork)
        } finally {
            if (!scratch.deleteRecursively())
                android.util.Log.w("FullBackup", "Could not remove validation database directory")
        }
    }
}
