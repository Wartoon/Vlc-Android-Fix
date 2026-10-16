package org.videolan.television.ui.preferences

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TvFullBackupStorageTest {
    @Test
    fun tvFullBackupUsesDedicatedVlcFolder() {
        val root = Files.createTempDirectory("tv-full-backup").toFile()
        try {
            assertEquals(
                File(root, "VLC/VLC TV App Backup/VLC-Full-Backup.zip"),
                tvFullBackupFile(root)
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
