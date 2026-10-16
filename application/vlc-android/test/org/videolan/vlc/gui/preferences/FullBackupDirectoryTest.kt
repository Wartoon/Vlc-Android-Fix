package org.videolan.vlc.gui.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class FullBackupDirectoryTest {
    private val root = File("/storage/emulated/0")

    @Test fun internalStorageBackupUsesItsOwnDirectory() {
        val file = dualBackupFile(root, "org.videolan.vlc.internalstorage")
        assertEquals(
            "/storage/emulated/0/VLC/VLC Internal Storage App Backup/VLC Complete Backup - Internal Storage.zip",
            file.path
        )
    }

    @Test fun smbBackupUsesItsOwnDirectory() {
        val file = dualBackupFile(root, "org.videolan.vlc.smb")
        assertEquals(
            "/storage/emulated/0/VLC/VLC SMB App Backup/VLC Complete Backup - SMB.zip",
            file.path
        )
    }

    @Test fun separateAppsCannotOverwriteEachOthersBackups() {
        val internal = dualBackupFile(root, "org.videolan.vlc.internalstorage")
        val smb = dualBackupFile(root, "org.videolan.vlc.smb")
        assertNotEquals(internal.path, smb.path)
        assertEquals(internal.parentFile?.parentFile, smb.parentFile?.parentFile)
    }

    @Test fun otherPackagesMustNotAccessEitherBackupDirectory() {
        try {
            dualBackupFile(root, "org.videolan.vlc.tvfix")
            fail("Only VLC Dual packages are supported")
        } catch (_: IllegalStateException) {
            // Correctly rejected.
        }
    }
}
