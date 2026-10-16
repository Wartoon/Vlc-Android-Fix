package org.videolan.vlc.gui.preferences

import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Location of the TV application's full backup in shared device storage. */
internal fun tvFullBackupFile(storageRoot: File): File =
    File(File(File(storageRoot, "VLC"), "VLC TV App Backup"), "VLC-Full-Backup.zip")

/** Stage in the destination directory so the final rename stays on one filesystem. */
internal fun createFullBackupStagingFile(destination: File): File {
    val directory = destination.parentFile ?: throw IOException("Backup directory missing")
    if (!directory.isDirectory && !directory.mkdirs())
        throw IOException("Cannot create backup directory: $directory")
    return File.createTempFile("vlc-full-backup-", ".zip.tmp", directory)
}

/**
 * Verify every ZIP entry (size and CRC), then atomically replace the fixed
 * manual-backup filename. Never delete the existing backup before success.
 */
internal fun verifyAndReplaceFullBackup(staged: File, destination: File) {
    val mandatory = setOf("manifest.json", "settings/settings.json", PlaybackStateBackup.ENTRY)
    val seen = HashSet<String>()
    var hasMediaDatabase = false
    ZipFile(staged).use { archive ->
        val entries = archive.entries()
        val buffer = ByteArray(64 * 1024)
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!seen.add(entry.name)) throw IOException("Duplicate full-backup entry: ${entry.name}")
            if (entry.isDirectory) continue
            val crc = CRC32()
            var size = 0L
            archive.getInputStream(entry).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                    size += read
                }
            }
            if (entry.size != size || entry.crc != crc.value)
                throw IOException("Corrupt full-backup entry: ${entry.name}")
            if (entry.name.startsWith("medialibrary/db/") && size > 0L)
                hasMediaDatabase = true
            if (entry.name in mandatory && size == 0L)
                throw IOException("Empty required full-backup entry: ${entry.name}")
        }
    }
    if (!seen.containsAll(mandatory) || !hasMediaDatabase)
        throw IOException("Incomplete full backup: missing mandatory data")
    // Ensure the fully-written archive reaches storage before replacing the old copy.
    RandomAccessFile(staged, "rw").use { it.fd.sync() }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // An atomic same-directory move replaces the existing ZIP only once
            // its replacement has been completely checked. Also works in JVM tests.
            Files.move(
                staged.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } else {
            // java.nio.file is unavailable below API 26.
            Os.rename(staged.absolutePath, destination.absolutePath)
        }
    } catch (e: Exception) {
        throw IOException("Could not safely replace previous full backup", e)
    }
}
