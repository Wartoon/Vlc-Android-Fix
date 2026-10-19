package org.videolan.vlc.gui.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class FullBackupRestoreTransactionTest {
    @Test fun unreadableSourceDirectoryFailsWithoutReplacingExistingTarget() = withTemporaryFolder { root ->
        val backingSource = File(root, "source").apply { mkdirs(); resolve("value").writeText("new") }
        val source = object : File(backingSource.path) {
            override fun listFiles(): Array<File>? = null
        }
        val target = File(root, "target").apply { mkdirs(); resolve("value").writeText("old") }

        try {
            runBlocking { FullBackupRestoreTransaction.replace(listOf(source to target)) }
            throw AssertionError("Expected unreadable source directory to fail")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("Cannot list restore source"))
        }

        assertEquals("old", File(target, "value").readText())
        assertFalse(root.listFiles()!!.any { it.name.contains("restore-new-") || it.name.contains("restore-old-") })
    }

    @Test fun failureAfterInstallRestoresEveryPreviousComponent() = withTemporaryFolder { root ->
        val sourceA = File(root, "source-a").apply { mkdirs(); resolve("value").writeText("new-a") }
        val sourceB = File(root, "source-b").apply { mkdirs(); resolve("value").writeText("new-b") }
        val targetA = File(root, "target-a").apply { mkdirs(); resolve("value").writeText("old-a") }
        val targetB = File(root, "target-b").apply { mkdirs(); resolve("value").writeText("old-b") }

        try {
            runBlocking {
                FullBackupRestoreTransaction.replace(listOf(sourceA to targetA, sourceB to targetB)) {
                    throw IOException("simulate settings restore failure")
                }
            }
            throw AssertionError("Expected transaction failure")
        } catch (expected: IOException) {
            assertEquals("simulate settings restore failure", expected.message)
        }

        assertEquals("old-a", File(targetA, "value").readText())
        assertEquals("old-b", File(targetB, "value").readText())
        assertFalse(root.listFiles()!!.any { it.name.contains("restore-new-") || it.name.contains("restore-old-") })
    }

    @Test fun failureDuringStagingRemovesEveryPreparedCopy() = withTemporaryFolder { root ->
        val sourceA = File(root, "source-a").apply { mkdirs(); resolve("value").writeText("new-a") }
        val sourceB = File(root, "source-b").apply { mkdirs() }
        Files.createSymbolicLink(File(sourceB, "broken-link").toPath(), File(root, "missing").toPath())
        val targetA = File(root, "target-a").apply { mkdirs(); resolve("value").writeText("old-a") }
        val targetB = File(root, "target-b")

        try {
            runBlocking {
                FullBackupRestoreTransaction.replace(listOf(sourceA to targetA, sourceB to targetB))
            }
            throw AssertionError("Expected staging failure")
        } catch (_: IOException) {
            // The broken source must leave neither a partial copy nor target changes.
        }

        assertEquals("old-a", File(targetA, "value").readText())
        assertFalse(root.listFiles()!!.any { it.name.contains("restore-new-") || it.name.contains("restore-old-") })
    }

    @Test fun afterInstallFailureRunsPreferenceRollbackBeforeReturningFailure() = withTemporaryFolder { root ->
        val source = File(root, "source").apply { mkdirs(); resolve("value").writeText("new") }
        val target = File(root, "target").apply { mkdirs(); resolve("value").writeText("old") }
        var rollbackRan = false

        try {
            runBlocking {
                FullBackupRestoreTransaction.replace(
                    targets = listOf(source to target),
                    afterInstall = { throw IOException("simulate playback restore failure") },
                    rollbackAfterInstall = { rollbackRan = true }
                )
            }
            throw AssertionError("Expected transaction failure")
        } catch (expected: IOException) {
            assertEquals("simulate playback restore failure", expected.message)
        }

        assertTrue(rollbackRan)
        assertEquals("old", File(target, "value").readText())
    }

    @Test fun successfulTransactionInstallsAllComponents() = withTemporaryFolder { root ->
        val source = File(root, "source").apply { mkdirs(); resolve("value").writeText("new") }
        val target = File(root, "target").apply { mkdirs(); resolve("value").writeText("old") }

        var callbackRan = false
        runBlocking {
            FullBackupRestoreTransaction.replace(listOf(source to target)) { callbackRan = true }
        }

        assertTrue(callbackRan)
        assertEquals("new", File(target, "value").readText())
        assertFalse(root.listFiles()!!.any { it.name.contains("restore-new-") || it.name.contains("restore-old-") })
    }

    private fun withTemporaryFolder(run: (File) -> Unit) {
        val root = File.createTempFile("vlc-restore-transaction-", "-tests")
        root.delete()
        check(root.mkdirs())
        try { run(root) } finally { root.deleteRecursively() }
    }
}
