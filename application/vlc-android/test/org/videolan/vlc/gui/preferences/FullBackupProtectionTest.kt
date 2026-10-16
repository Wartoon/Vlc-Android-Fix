package org.videolan.vlc.gui.preferences

import androidx.sqlite.driver.bundled.BundledSQLite
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Regression guards for protecting the only retained full backup. */
@RunWith(RobolectricTestRunner::class)
class FullBackupProtectionTest {
    private fun snapshot(
        media: Long = 100L,
        folders: Long = 3L,
        playlists: Long = 2L,
        artwork: Long = 8L,
        paths: Set<String> = setOf("/media/a", "/media/b", "/media/c")
    ) = FullBackupLibrarySnapshot(media, folders, playlists, artwork, paths)

    @Test fun sameLibraryCanReplacePreviousBackup() {
        val previous = snapshot()
        assertNull(fullBackupReduction(previous, previous))
    }

    @Test fun growingLibraryCanReplacePreviousBackup() {
        val previous = snapshot()
        val next = snapshot(
            media = 101L, folders = 4L, playlists = 3L, artwork = 9L,
            paths = previous.folderPaths + "/media/d"
        )
        assertNull(fullBackupReduction(previous, next))
    }

    @Test fun lostMediaMustNotSilentlyReplacePreviousBackup() {
        val reason = fullBackupReduction(snapshot(), snapshot(media = 99L))
        assertTrue(reason?.contains("Media: 100 -> 99") == true)
    }

    @Test fun missingSourceIsDetectedEvenIfFolderCountIsUnchanged() {
        val reason = fullBackupReduction(
            snapshot(), snapshot(paths = setOf("/media/a", "/media/b", "/media/other"))
        )
        assertTrue(reason?.contains("Missing media folders: 1") == true)
    }

    @Test fun reducedPlaylistsAndArtworkAreReported() {
        val reason = fullBackupReduction(
            snapshot(), snapshot(playlists = 1L, artwork = 7L)
        )
        assertTrue(reason?.contains("Playlists: 2 -> 1") == true)
        assertTrue(reason?.contains("Artwork: 8 -> 7") == true)
    }

    @Test fun missingFolderCountIsDetected() {
        val reason = fullBackupReduction(
            snapshot(), snapshot(folders = 2L, paths = setOf("/media/a", "/media/b"))
        )
        assertTrue(reason?.contains("Folders: 3 -> 2") == true)
        assertTrue(reason?.contains("Missing media folders: 1") == true)
    }

    @Test fun reductionContainsAllDetectedLosses() {
        val reason = fullBackupReduction(
            snapshot(),
            snapshot(media = 1L, folders = 1L, playlists = 0L, artwork = 0L,
                paths = setOf("/media/a"))
        ) ?: throw AssertionError("Expected reduction")
        assertEquals(5, reason.split("; ").size)
    }

    @Test
    @Config(sdk = [28])
    fun androidNineCanValidateDatabaseWithUpdateFromTrigger() {
        val directory = createTempDir(prefix = "vlc-backup-sqlite-")
        val database = File(directory, "vlc_media.db")
        val staged = File(directory, "staged.zip")
        val destination = File(directory, "backup.zip")
        createDatabaseWithModernTrigger(database)
        ZipOutputStream(FileOutputStream(staged)).use { zip ->
            zip.putEntry("manifest.json", "{}".toByteArray())
            zip.putEntry("settings/settings.json", "{}".toByteArray())
            zip.putEntry(PlaybackStateBackup.ENTRY, "{}".toByteArray())
            zip.putEntry("medialibrary/db/vlc_media.db", database.readBytes())
        }

        verifyProtectedSingleFullBackup(staged, destination, directory)

        assertTrue(destination.isFile)
    }

    private fun createDatabaseWithModernTrigger(database: File) {
        val connection = BundledSQLiteDriver().open(
            database.absolutePath,
            BundledSQLite.SQLITE_OPEN_READWRITE or BundledSQLite.SQLITE_OPEN_CREATE
        )
        connection.use {
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

    private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
