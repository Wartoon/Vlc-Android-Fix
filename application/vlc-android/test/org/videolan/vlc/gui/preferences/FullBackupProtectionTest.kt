package org.videolan.vlc.gui.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Regression guards for protecting the only retained full backup. */
class FullBackupProtectionTest {
    @Test fun backupCannotBeAcceptedWithoutAppDatabaseEntries() {
        try {
            validateAppDbBackupEntries(emptyList())
            fail("Expected missing app database to be rejected")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("App database missing"))
        }
    }

    @Test fun backupAcceptsNonEmptyAppDatabaseEntry() {
        validateAppDbBackupEntries(listOf("appdb/favorites.db" to 1L))
    }

    @Test fun backupAcceptsDualRoomDatabaseNamesWithoutExtensions() {
        validateAppDbBackupEntries(listOf(
            "appdb/vlc_database" to 1L,
            "appdb/moviepedia_database" to 1L
        ))
    }

    @Test fun restoreRecognizesDualRoomDatabaseNamesWithoutExtensions() {
        assertTrue(isRestoreDatabaseFileName("vlc_database"))
        assertTrue(isRestoreDatabaseFileName("moviepedia_database"))
        assertFalse(isRestoreDatabaseFileName("vlc_database-wal"))
        assertFalse(isRestoreDatabaseFileName("vlc_database-shm"))
    }

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

}
