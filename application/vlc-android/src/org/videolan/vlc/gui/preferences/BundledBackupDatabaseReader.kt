package org.videolan.vlc.gui.preferences

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** Reads a staged VLC media database with the SQLite version shipped in the APK. */
object BundledBackupDatabaseReader {
    private const val MAX_SNAPSHOT_ATTEMPTS = 3
    private const val WAL_HEADER_SIZE = 32L

    /**
     * Checkpoint committed WAL pages, acquire SQLite's writer lock, then copy
     * the now self-contained database file. A raw snapshot preserves VLC's
     * native-only FILENAME collation, which AndroidX's bundled driver cannot
     * register and therefore cannot use with VACUUM INTO.
     */
    @JvmStatic
    fun backup(source: File, destination: File) {
        check(source.isFile) { "Backup source database is missing" }
        check(!destination.exists() || destination.length() == 0L) {
            "Backup destination must be absent or empty"
        }
        try {
            val wal = File(source.parentFile, source.name + "-wal")
            var copied = false
            repeat(MAX_SNAPSHOT_ATTEMPTS) { attempt ->
                if (copied) return@repeat
                val db = BundledSQLiteDriver().open(source.absolutePath)
                var transactionOpen = false
                try {
                    if (!checkpointCommittedWal(db)) {
                        Thread.sleep(100L * (attempt + 1))
                        return@repeat
                    }
                    db.prepare("BEGIN IMMEDIATE").use { check(!it.step()) { "Could not lock source database" } }
                    transactionOpen = true
                    // Another connection may have committed between checkpoint and lock.
                    // Once BEGIN IMMEDIATE succeeds, no writer can append more frames.
                    if (wal.isFile && wal.length() > WAL_HEADER_SIZE) {
                        db.prepare("ROLLBACK").use { it.step() }
                        transactionOpen = false
                        Thread.sleep(100L * (attempt + 1))
                        return@repeat
                    }
                    FileOutputStream(destination, false).use { output ->
                        source.inputStream().use { it.copyTo(output) }
                        output.fd.sync()
                    }
                    RandomAccessFile(destination, "rw").use { it.fd.sync() }
                    db.prepare("ROLLBACK").use { it.step() }
                    transactionOpen = false
                    copied = true
                } finally {
                    if (transactionOpen) runCatching { db.prepare("ROLLBACK").use { it.step() } }
                    db.close()
                }
            }
            if (!copied) throw IOException("Could not obtain a stable SQLite database snapshot")
            check(destination.isFile && destination.length() > 0L) {
                "SQLite did not create a database backup"
            }
            validate(destination)
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    private fun checkpointCommittedWal(db: androidx.sqlite.SQLiteConnection): Boolean =
        db.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { statement ->
            check(statement.step()) { "SQLite WAL checkpoint returned no status" }
            val busy = statement.getLong(0)
            val logFrames = statement.getLong(1)
            val checkpointedFrames = statement.getLong(2)
            busy == 0L && (logFrames < 0L || checkpointedFrames >= logFrames)
        }

    @JvmStatic
    fun validate(file: File) {
        val db = BundledSQLiteDriver().open(file.absolutePath, 0x00000001)
        try {
            val usesNativeFilenameCollation = db.prepare(
                "SELECT 1 FROM sqlite_schema WHERE sql IS NOT NULL AND " +
                    "instr(upper(sql), 'COLLATE FILENAME') > 0 LIMIT 1"
            ).use { it.step() }
            if (usesNativeFilenameCollation) {
                // quick_check must compare index keys and fails without VLC's
                // native collation. Check the catalog here; data reads and ZIP
                // CRC checks validate the copied snapshot without rewriting it.
                db.prepare("SELECT COUNT(*) FROM sqlite_schema").use { statement ->
                    check(statement.step() && statement.getLong(0) > 0L) {
                        "Restore database schema validation failed"
                    }
                }
            } else {
                db.prepare("PRAGMA quick_check").use { statement ->
                    check(statement.step()) { "Restore database integrity check returned no result" }
                    do {
                        check(statement.getText(0) == "ok") { "Restore database integrity check failed" }
                    } while (statement.step())
                }
            }
        } finally {
            db.close()
        }
    }

    @JvmStatic
    internal fun read(file: File, artwork: Long): FullBackupLibrarySnapshot {
        val db = BundledSQLiteDriver().open(file.absolutePath, 0x00000001)
        try {
            fun count(table: String): Long {
                db.prepare("SELECT COUNT(*) FROM $table").use { statement ->
                    check(statement.step()) { "Cannot count $table" }
                    return statement.getLong(0)
                }
            }
            val paths = HashSet<String>()
            db.prepare("SELECT path FROM Folder WHERE path IS NOT NULL").use { statement ->
                while (statement.step()) paths += statement.getText(0)
            }
            return FullBackupLibrarySnapshot(count("Media"), count("Folder"),
                count("Playlist"), artwork, paths)
        } finally {
            db.close()
        }
    }
}
