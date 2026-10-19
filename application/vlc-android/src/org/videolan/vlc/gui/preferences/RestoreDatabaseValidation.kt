package org.videolan.vlc.gui.preferences

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException

/** Reject corrupt or non-SQLite databases before any live restore target is changed. */
fun validateRestoreDatabase(file: File) {
    if (!file.isFile || file.length() == 0L) throw IOException("Restore database is missing or empty: ${file.name}")
    val database = try {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    } catch (e: Exception) {
        throw IOException("Restore database cannot be opened: ${file.name}", e)
    }
    try {
        database.rawQuery("PRAGMA quick_check", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "ok")
                throw IOException("Restore database integrity check failed: ${file.name}")
            while (cursor.moveToNext()) {
                if (cursor.getString(0) != "ok")
                    throw IOException("Restore database integrity check failed: ${file.name}")
            }
        }
    } catch (e: Exception) {
        if (e is IOException) throw e
        throw IOException("Restore database integrity check failed: ${file.name}", e)
    } finally {
        database.close()
    }
}

fun validateRestoreDatabaseDirectory(directory: File) {
    if (!directory.isDirectory) throw IOException("Restore database directory is missing")
    val databases = directory.walkTopDown().filter {
        it.isFile && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") &&
            !it.name.endsWith("-journal") && (it.name.endsWith(".db") || it.name.endsWith(".sqlite"))
    }.toList()
    if (databases.isEmpty()) throw IOException("Restore database directory contains no databases")
    databases.forEach(::validateRestoreDatabase)
}
