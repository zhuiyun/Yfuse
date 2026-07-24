package com.yfuse.core.data

import com.russhwolf.settings.Settings

/** Recent search terms, newest first, persisted across launches. */
class SearchHistory(private val settings: Settings) {

    private companion object {
        const val KEY = "search.recent"

        /** Newline, not space: search terms routinely contain spaces. */
        const val SEPARATOR = "\n"

        const val LIMIT = 8
    }

    fun load(): List<String> =
        settings.getStringOrNull(KEY)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /** Moves [term] to the front, de-duplicated and capped at [LIMIT]. */
    fun remember(term: String): List<String> {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return load()
        val updated = (listOf(trimmed) + load().filterNot { it == trimmed }).take(LIMIT)
        settings.putString(KEY, updated.joinToString(SEPARATOR))
        return updated
    }

    fun remove(term: String): List<String> {
        val updated = load().filterNot { it == term }
        settings.putString(KEY, updated.joinToString(SEPARATOR))
        return updated
    }

    fun clear(): List<String> {
        settings.remove(KEY)
        return emptyList()
    }
}
