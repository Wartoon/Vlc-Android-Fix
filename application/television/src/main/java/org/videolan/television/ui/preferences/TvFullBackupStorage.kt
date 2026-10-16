package org.videolan.television.ui.preferences

import java.io.File

/** Canonical location for the TV application's full backup in shared storage. */
internal fun tvFullBackupFile(storageRoot: File): File =
    File(File(File(storageRoot, "VLC"), "VLC TV App Backup"), "VLC-Full-Backup.zip")
