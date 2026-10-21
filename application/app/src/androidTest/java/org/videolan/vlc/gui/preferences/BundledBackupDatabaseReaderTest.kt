package org.videolan.vlc.gui.preferences

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class BundledBackupDatabaseReaderTest {
    @Test fun backupIncludesCommittedWalRowsInOneValidatedFile() {
        val root = Files.createTempDirectory("vlc-bundled-sqlite-backup-").toFile()
        val source = File(root, "source.db")
        val destination = File(root, "backup.db")
        val driver = BundledSQLiteDriver()
        val live = driver.open(source.absolutePath)
        try {
            live.prepare("PRAGMA journal_mode=WAL").use { assertTrue(it.step()) }
            live.prepare("CREATE TABLE items (name TEXT NOT NULL)").use { assertTrue(!it.step()) }
            live.prepare("INSERT INTO items VALUES ('committed')").use { assertTrue(!it.step()) }
            assertTrue(File(root, "source.db-wal").isFile)

            BundledBackupDatabaseReader.backup(source, destination)

            val backup = driver.open(destination.absolutePath, 0x00000001)
            try {
                backup.prepare("SELECT name FROM items").use { statement ->
                    assertTrue(statement.step())
                    assertEquals("committed", statement.getText(0))
                }
            } finally {
                backup.close()
            }
        } finally {
            live.close()
            root.deleteRecursively()
        }
    }

    @Test fun backupPreservesMediaDatabaseWithCustomFilenameCollation() {
        val root = Files.createTempDirectory("vlc-bundled-sqlite-collation-backup-").toFile()
        val source = File(root, "source.db")
        val destination = File(root, "backup.db")
        val driver = BundledSQLiteDriver()
        val live = driver.open(source.absolutePath)
        try {
            live.prepare("PRAGMA journal_mode=WAL").use { assertTrue(it.step()) }
            live.prepare("CREATE TABLE Media (fileName TEXT NOT NULL)").use { assertTrue(!it.step()) }
            live.prepare("CREATE INDEX Media_fileName ON Media(fileName)").use { assertTrue(!it.step()) }
            live.prepare("INSERT INTO Media VALUES ('episode-10.mkv')").use { assertTrue(!it.step()) }
            live.prepare("INSERT INTO Media VALUES ('episode-2.mkv')").use { assertTrue(!it.step()) }

            // Model the VLC media DB schema: its native connection registers FILENAME,
            // but AndroidX's bundled driver intentionally does not know that collation.
            live.prepare("PRAGMA writable_schema=ON").use { assertTrue(!it.step()) }
            live.prepare("UPDATE sqlite_schema SET sql=? WHERE name=?").use {
                it.bindText(1, "CREATE INDEX Media_fileName ON Media(fileName COLLATE FILENAME)")
                it.bindText(2, "Media_fileName")
                assertTrue(!it.step())
            }
            live.prepare("PRAGMA schema_version=99").use { assertTrue(!it.step()) }
            live.prepare("PRAGMA writable_schema=OFF").use { assertTrue(!it.step()) }
            assertTrue(File(root, "source.db-wal").length() > 32L)

            BundledBackupDatabaseReader.backup(source, destination)

            val backup = driver.open(destination.absolutePath, 0x00000001)
            try {
                backup.prepare("SELECT COUNT(*) FROM Media").use { statement ->
                    assertTrue(statement.step())
                    assertEquals(2L, statement.getLong(0))
                }
                backup.prepare("SELECT sql FROM sqlite_schema WHERE name='Media_fileName'").use { statement ->
                    assertTrue(statement.step())
                    assertTrue(statement.getText(0).contains("COLLATE FILENAME"))
                }
            } finally {
                backup.close()
            }
        } finally {
            live.close()
            root.deleteRecursively()
        }
    }
}
