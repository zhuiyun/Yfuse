package com.yfuse.feature.library

import com.russhwolf.settings.Settings
import com.yfuse.core.model.LibrarySort

/** Remembers the sort chosen for each library, so a grid opens the way it was left. */
class LibrarySortMemory(
    private val settings: Settings,
) {
    fun read(libraryId: String): LibrarySort? =
        settings
            .getStringOrNull(key(libraryId))
            ?.let { name -> LibrarySort.entries.firstOrNull { it.name == name } }

    fun write(
        libraryId: String,
        sort: LibrarySort,
    ) {
        settings.putString(key(libraryId), sort.name)
    }

    private fun key(libraryId: String): String = "library.sort.v1.${libraryId.take(MAX_KEY_ID_CHARS)}"

    private companion object {
        const val MAX_KEY_ID_CHARS = 120
    }
}
