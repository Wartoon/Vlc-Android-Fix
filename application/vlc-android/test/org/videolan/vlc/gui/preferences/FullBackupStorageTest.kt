package org.videolan.vlc.gui.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FullBackupStorageTest {
    @Test fun tvBackupLivesBelowSharedVlcDirectory() {
        val result = tvFullBackupFile(File("/storage/emulated/0"))
        assertEquals("/storage/emulated/0/VLC/VLC TV App Backup/VLC-Full-Backup.zip", result.path)
    }

    @Test fun malformedBackupNeverReplacesExistingBackup() = withTemporaryFolder { root ->
        val target = File(root, "old.zip")
        target.writeText("previous backup remains unchanged")
        val staged = createFullBackupStagingFile(target)
        staged.writeText("not a zip")
        try {
            verifyAndReplaceFullBackup(staged, target)
            fail("Malformed backup was accepted")
        } catch (_: IOException) {
            assertEquals("previous backup remains unchanged", target.readText())
            assertTrue(staged.exists())
        }
    }

    @Test fun validBackupAtomicallyReplacesPreviousBackup() = withTemporaryFolder { root ->
        val target = File(root, "old.zip")
        target.writeText("previous")
        val staged = createFullBackupStagingFile(target)
        ZipOutputStream(staged.outputStream()).use { zip ->
            for (name in arrayOf("manifest.json", "settings/settings.json",
                    PlaybackStateBackup.ENTRY, "medialibrary/db/vlc_media.db")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("backup-data".toByteArray())
                zip.closeEntry()
            }
        }
        verifyAndReplaceFullBackup(staged, target)
        assertTrue(target.isFile)
        assertFalse(staged.exists())
        assertNotEquals("previous", target.readText())
    }

    @Test fun missingMediaDatabaseDoesNotReplaceBackup() = withTemporaryFolder { root ->
        val target = File(root, "backup.zip")
        target.writeText("old")
        val staged = createFullBackupStagingFile(target)
        ZipOutputStream(staged.outputStream()).use { zip ->
            for (name in arrayOf("manifest.json", "settings/settings.json", PlaybackStateBackup.ENTRY)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("notempty".toByteArray())
                zip.closeEntry()
            }
        }
        try {
            verifyAndReplaceFullBackup(staged, target)
            fail("Incomplete backup was accepted")
        } catch (_: IOException) {
            assertEquals("old", target.readText())
        }
    }


    @Test fun manualAndAutomaticShareThreeGenerations() = withTemporaryFolder { root ->
        val primary = File(root, "VLC-Full-Backup.zip")
        fun writeVersion(n: Int) {
            val staged = createFullBackupStagingFile(primary)
            ZipOutputStream(staged.outputStream()).use { zip ->
                for (name in arrayOf("manifest.json", "settings/settings.json",
                        PlaybackStateBackup.ENTRY, "medialibrary/db/vlc_media.db")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write("version-$n".toByteArray())
                    zip.closeEntry()
                }
            }
            verifyRotateAndReplaceFullBackup(staged, primary)
        }
        fun value(file: File): String {
            java.util.zip.ZipFile(file).use { z ->
                return z.getInputStream(z.getEntry("manifest.json")).bufferedReader().readText()
            }
        }
        (1..4).forEach(::writeVersion)
        assertEquals("version-4", value(primary))
        assertEquals("version-3", value(File(root, "VLC-Full-Backup - Previous 1.zip")))
        assertEquals("version-2", value(File(root, "VLC-Full-Backup - Previous 2.zip")))
        assertEquals(3, root.listFiles()!!.count { it.name.endsWith(".zip") })
    }

    @Test fun failedNewBackupLeavesAllPreviousGenerations() = withTemporaryFolder { root ->
        val main = File(root, "VLC-Full-Backup.zip")
        val historical = File(root, "VLC-Full-Backup - Previous 1.zip")
        main.writeText("existing")
        historical.writeText("older")
        val staged = createFullBackupStagingFile(main)
        staged.writeText("bad")
        try {
            verifyRotateAndReplaceFullBackup(staged, main)
            fail("Invalid ZIP accepted")
        } catch (_: IOException) {
            assertEquals("existing", main.readText())
            assertEquals("older", historical.readText())
        }
    }

    private fun withTemporaryFolder(run: (File) -> Unit) {
        val root = File.createTempFile("vlc-full-backup-", "-tests")
        root.delete()
        assertTrue(root.mkdirs())
        try {
            run(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
