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
}
