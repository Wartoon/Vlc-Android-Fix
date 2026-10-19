package org.videolan.vlc.gui.preferences

import java.io.File
import java.io.IOException

/** Installs all selected restore components as one rollback-capable filesystem transaction. */
object FullBackupRestoreTransaction {
    private data class Operation(
        val source: File,
        val destination: File,
        val staged: File,
        val previous: File,
        var previousMoved: Boolean = false,
        var replacementInstalled: Boolean = false
    )

    suspend fun replace(targets: List<Pair<File, File>>, afterInstall: suspend () -> Unit = {}) {
        if (targets.isEmpty()) {
            afterInstall()
            return
        }
        val destinations = targets.map { it.second.canonicalPath }
        if (destinations.toSet().size != destinations.size)
            throw IOException("Restore contains duplicate destinations")

        val operations = targets.map { (source, destination) ->
            if (!source.exists()) throw IOException("Restore source is missing: ${source.name}")
            val parent = destination.parentFile ?: throw IOException("Restore destination has no parent")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create restore destination")
            val token = System.nanoTime().toString()
            val staged = File(parent, "${destination.name}.restore-new-$token")
            val previous = File(parent, "${destination.name}.restore-old-$token")
            copy(source, staged)
            Operation(source, destination, staged, previous)
        }

        var committed = false
        try {
            operations.forEach { operation ->
                if (operation.destination.exists()) {
                    if (!operation.destination.renameTo(operation.previous))
                        throw IOException("Cannot preserve existing ${operation.destination.name}")
                    operation.previousMoved = true
                }
                if (!operation.staged.renameTo(operation.destination))
                    throw IOException("Cannot install ${operation.destination.name}")
                operation.replacementInstalled = true
            }
            afterInstall()
            committed = true
        } catch (failure: Exception) {
            operations.asReversed().forEach { operation ->
                if (operation.replacementInstalled && operation.destination.exists() &&
                    !operation.destination.deleteRecursively())
                    failure.addSuppressed(IOException("Cannot remove incomplete ${operation.destination.name}"))
                if (operation.previousMoved && operation.previous.exists() &&
                    !operation.previous.renameTo(operation.destination))
                    failure.addSuppressed(IOException("Cannot roll back ${operation.destination.name}"))
            }
            throw failure
        } finally {
            operations.forEach { operation ->
                operation.staged.deleteRecursively()
                if (committed) operation.previous.deleteRecursively()
            }
        }
    }

    private fun copy(source: File, destination: File) {
        if (source.isDirectory) {
            if (!destination.mkdirs()) throw IOException("Cannot stage restore component: ${source.name}")
            source.listFiles()?.forEach { child -> copy(child, File(destination, child.name)) }
        } else {
            val parent = destination.parentFile ?: throw IOException("Restore staging has no parent")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create restore staging")
            source.inputStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
        }
    }
}
