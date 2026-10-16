package org.videolan.vlc.gui.preferences

import androidx.sqlite.driver.bundled.BundledSQLite
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BundledBackupDatabaseReaderTest {
    @Test
    fun readsMediaCountsFromDatabaseWithUpdateFromTrigger() {
        val database = File.createTempFile("vlc-backup-reader-", ".db")
        database.delete()
        createDatabaseWithUpdateFromTrigger(database)

        val result = BundledBackupDatabaseReader.read(database, artwork = 4L)

        assertEquals(1L, result.media)
        assertEquals(1L, result.folders)
        assertEquals(0L, result.playlists)
        assertEquals(4L, result.artwork)
        assertEquals(setOf("/media"), result.folderPaths)
    }

    private fun createDatabaseWithUpdateFromTrigger(database: File) {
        BundledSQLiteDriver().open(
            database.absolutePath,
            BundledSQLite.SQLITE_OPEN_READWRITE or BundledSQLite.SQLITE_OPEN_CREATE
        ).use { connection ->
            listOf(
                "CREATE TABLE Media(id_media INTEGER PRIMARY KEY, play_count INTEGER)",
                "CREATE TABLE Folder(path TEXT)",
                "CREATE TABLE Playlist(id_playlist INTEGER PRIMARY KEY)",
                "CREATE TABLE Subscription(id_subscription INTEGER PRIMARY KEY, nb_unplayed_media INTEGER)",
                "CREATE TABLE SubscriptionMediaRelation(media_id INTEGER, subscription_id INTEGER)",
                "INSERT INTO Media(id_media, play_count) VALUES(1, 0)",
                "INSERT INTO Folder(path) VALUES('/media')",
                "CREATE TRIGGER subscription_update_unplayed_media AFTER UPDATE OF play_count ON Media " +
                    "WHEN old.play_count != new.play_count AND (old.play_count = 0 OR new.play_count = 0) " +
                    "BEGIN UPDATE Subscription SET nb_unplayed_media = nb_unplayed_media + (items.cnt * " +
                    "IIF(new.play_count = 0, 1, -1)) FROM (SELECT COUNT(media_id) AS cnt, subscription_id " +
                    "FROM SubscriptionMediaRelation WHERE media_id = old.id_media GROUP BY subscription_id) " +
                    "AS items WHERE id_subscription = items.subscription_id; END"
            ).forEach { sql -> connection.prepare(sql).use { it.step() } }
        }
    }
}
