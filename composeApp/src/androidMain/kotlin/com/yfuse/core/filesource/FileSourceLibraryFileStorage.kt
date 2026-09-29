package com.yfuse.core.filesource

import java.io.File

/**
 * The library document in the app's private files — not the cache, which the system may clear
 * while the share still holds every file the library names.
 *
 * Written beside itself and renamed over, so a process killed mid-write leaves the previous
 * library rather than half of the new one.
 */
internal class FileSourceLibraryFileStorage(
    private val directory: () -> File?,
) : FileSourceLibraryStorage {
    override fun read(): String? =
        directory()
            ?.resolve(FILE_NAME)
            ?.takeIf(File::isFile)
            ?.readText()

    override fun write(text: String) {
        val folder = directory() ?: error("No files directory")
        val target = File(folder, FILE_NAME)
        val staged = File(folder, "$FILE_NAME.tmp")
        staged.writeText(text)
        if (!staged.renameTo(target)) {
            staged.delete()
            error("The library could not be moved into place")
        }
    }

    private companion object {
        const val FILE_NAME = "filesource-library-v1.json"
    }
}
