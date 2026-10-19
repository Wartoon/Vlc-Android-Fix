package org.videolan.vlc.gui.preferences

import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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
        replaceBySameDirectoryRename(staged, destination)
    } catch (e: Exception) {
        throw IOException("Could not safely replace previous full backup", e)
    }
}

/** Atomic on one filesystem on Android and JVMs; preserve the old file if fallback moves fail. */
private fun replaceBySameDirectoryRename(staged: File, destination: File) {
    if (staged.parentFile != destination.parentFile)
        throw IOException("Staged backup must share the destination directory")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        Os.rename(staged.absolutePath, destination.absolutePath)
        return
    }
    // API 17–20 do not provide android.system.Os. Keep a rollback copy while
    // using File.renameTo, which is same-filesystem and atomic on Android's FS.
    val previous = File(destination.parentFile, destination.name + ".previous-tmp")
    if (previous.exists() && !previous.delete()) throw IOException("Cannot clear prior backup rollback")
    val movedOld = destination.exists()
    if (movedOld && !destination.renameTo(previous)) throw IOException("Cannot stage previous backup")
    try {
        if (!staged.renameTo(destination)) throw IOException("Cannot install staged backup")
        if (previous.exists() && !previous.delete())
            android.util.Log.w("FullBackup", "Could not remove temporary previous backup")
    } catch (e: Exception) {
        if (destination.exists()) destination.delete()
        if (movedOld && previous.exists() && !previous.renameTo(destination))
            e.addSuppressed(IOException("Could not restore previous backup"))
        throw e
    }
}

/**
 * Stage the old TV backup from shared-storage root in the canonical folder.
 * Keep the root copy until a new full backup has been fully verified and installed.
 * Invalid legacy files remain available as a fallback and do not block a fresh backup.
 */
internal fun stageLegacyTvBackup(storageRoot: File): File? {
    val legacy = File(storageRoot, "VLC-Full-Backup.zip")
    if (!legacy.isFile) return null
    val destination = tvFullBackupFile(storageRoot)
    if (destination.exists()) return legacy

    val staged = createFullBackupStagingFile(destination)
    try {
        legacy.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
        verifyAndReplaceFullBackup(staged, destination)
    } catch (_: IOException) {
        android.util.Log.w("FullBackup", "Could not stage the legacy TV backup; preserving it")
    } finally {
        if (staged.exists()) staged.delete()
    }
    return legacy
}

/**
 * Three generations in total: primary ZIP and two historical ZIPs.
 * Save an independent copy of the previous primary before replacing it.
 * Both manual and automatic backups use this same rotation.
 */
internal fun verifyRotateAndReplaceFullBackup(staged: File, destination: File) {
    val dir = destination.parentFile ?: throw IOException("Missing full-backup folder")
    val previous1 = File(dir, "${destination.nameWithoutExtension} - Previous 1.zip")
    val previous2 = File(dir, "${destination.nameWithoutExtension} - Previous 2.zip")
    var savedOld: File? = null
    try {
        if (destination.isFile) {
            val previousCopy = File.createTempFile("vlc-previous-", ".zip.tmp", dir)
            savedOld = previousCopy
            destination.inputStream().use { input -> previousCopy.outputStream().use { output -> input.copyTo(output) } }
        }
        // This verifies all entries and atomically replaces ONLY the main ZIP.
        // A bad staged ZIP never changes that file or existing generations.
        verifyAndReplaceFullBackup(staged, destination)
        if (savedOld != null) {
            if (previous1.isFile) replaceBySameDirectoryRename(previous1, previous2)
            replaceBySameDirectoryRename(savedOld, previous1)
        }
    } finally {
        savedOld?.delete()
    }
}
