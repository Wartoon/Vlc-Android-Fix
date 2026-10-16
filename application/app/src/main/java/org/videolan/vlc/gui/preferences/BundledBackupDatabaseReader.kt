package org.videolan.vlc.gui.preferences

import androidx.sqlite.driver.bundled.BundledSQLite
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/** Reads a staged VLC media database with the SQLite version shipped in the APK. */
object BundledBackupDatabaseReader {
    @JvmStatic
    fun read(file: File, artwork: Long): FullBackupLibrarySnapshot {
        val db = BundledSQLiteDriver().open(file.absolutePath, BundledSQLite.SQLITE_OPEN_READONLY)
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
