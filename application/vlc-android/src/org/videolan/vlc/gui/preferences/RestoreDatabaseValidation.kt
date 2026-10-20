package org.videolan.vlc.gui.preferences

import android.os.Build
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

internal const val MAX_FULL_BACKUP_MANIFEST_BYTES = 64L * 1024L
internal const val MAX_STANDALONE_DATABASE_BACKUP_BYTES = 4L * 1024L * 1024L * 1024L
private const val MAX_STANDALONE_DATABASE_BACKUP_ENTRIES = 1024

/** Read a restore manifest with a byte cap, even if the file changes while read. */
fun readFullBackupManifest(file: File): String {
    val bytes = ByteArrayOutputStream()
    file.inputStream().use { input ->
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size().toLong() + count.toLong() > MAX_FULL_BACKUP_MANIFEST_BYTES)
                throw IOException("Backup manifest exceeds 64 KiB")
            bytes.write(buffer, 0, count)
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private val extensionlessRoomDatabaseNames = setOf("vlc_database", "moviepedia_database")

/** Keep archived paths compatible with every restore extractor and filesystem. */
internal fun validateFullBackupEntryPath(name: String) {
    val path = if (name.endsWith('/')) name.dropLast(1) else name
    if (path.isEmpty() || name.startsWith('/') || name.contains('\\') || name.contains('\u0000') ||
        path.split('/').any { it.isEmpty() || it == "." || it == ".." })
        throw IOException("Invalid backup ZIP entry path")
}

internal fun isRestoreDatabaseFileName(name: String): Boolean =
    name in extensionlessRoomDatabaseNames ||
        name.endsWith(".db") || name.endsWith(".db3") ||
        name.endsWith(".sqlite") || name.endsWith(".sqlite3")

/** Reject corrupt or non-SQLite databases before any live restore target is changed. */
fun validateRestoreDatabase(file: File) {
    if (!file.isFile || file.length() == 0L) throw IOException("Restore database is missing or empty: ${file.name}")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        try {
            val reader = Class.forName("org.videolan.vlc.gui.preferences.BundledBackupDatabaseReader")
            reader.getMethod("validate", File::class.java).invoke(null, file)
        } catch (e: InvocationTargetException) {
            throw IOException("Restore database integrity check failed: ${file.name}", e.targetException)
        } catch (e: Exception) {
            throw IOException("Bundled SQLite restore validation is unavailable: ${file.name}", e)
        } catch (e: LinkageError) {
            throw IOException("Bundled SQLite restore validation is unavailable: ${file.name}", e)
        }
        return
    }
    val database = try {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    } catch (e: Exception) {
        throw IOException("Restore database cannot be opened: ${file.name}", e)
    }
    try {
        database.rawQuery("PRAGMA quick_check", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "ok")
                throw IOException("Restore database integrity check failed: ${file.name}")
            while (cursor.moveToNext()) {
                if (cursor.getString(0) != "ok")
                    throw IOException("Restore database integrity check failed: ${file.name}")
            }
        }
    } catch (e: Exception) {
        if (e is IOException) throw e
        throw IOException("Restore database integrity check failed: ${file.name}", e)
    } finally {
        database.close()
    }
}

fun validateRestoreDatabaseDirectory(directory: File) {
    if (!directory.isDirectory) throw IOException("Restore database directory is missing")
    val databases = directory.walkTopDown().filter {
        it.isFile && isRestoreDatabaseFileName(it.name)
    }.toList()
    if (databases.isEmpty()) throw IOException("Restore database directory contains no databases")
    databases.forEach(::validateRestoreDatabase)
}

/** SQLite shared-memory files are transient and must never be restored from an archive. */
fun removeSqliteSharedMemoryFiles(directory: File) {
    if (!directory.isDirectory) return
    directory.walkTopDown().filter { it.isFile && it.name.endsWith("-shm") }.forEach {
        if (!it.delete()) throw IOException("Cannot remove transient SQLite shared-memory file")
    }
}

/** Safely stages the flat ZIP format written by the standalone app-database export. */
fun extractAppDatabaseBackup(source: File, destination: File) {
    if (!source.isFile) throw IOException("App database backup is missing")
    if (!destination.exists() && !destination.mkdirs()) throw IOException("Cannot create staged app database directory")
    val names = HashSet<String>()
    var totalBytes = 0L
    var entryCount = 0
    ZipInputStream(BufferedInputStream(FileInputStream(source))).use { zip ->
        val buffer = ByteArray(8192)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) throw IOException("App database backup contains a directory entry")
            val name = entry.name
            if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\'))
                throw IOException("App database backup contains an unsafe entry")
            if (!names.add(name)) throw IOException("App database backup contains a duplicate entry")
            entryCount++
            if (entryCount > MAX_STANDALONE_DATABASE_BACKUP_ENTRIES)
                throw IOException("App database backup contains too many entries")
            val output = File(destination, name)
            FileOutputStream(output).use { out ->
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > MAX_STANDALONE_DATABASE_BACKUP_BYTES)
                        throw IOException("App database backup exceeds 4 GiB")
                    out.write(buffer, 0, count)
                }
            }
            zip.closeEntry()
        }
    }
    if (entryCount == 0) throw IOException("App database backup is empty")
    removeSqliteSharedMemoryFiles(destination)
    validateRestoreDatabaseDirectory(destination)
}
