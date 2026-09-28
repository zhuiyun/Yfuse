package com.yfuse.feature.profile

import com.russhwolf.settings.Settings

/** Keep an explicit sort choice when the downloads page is closed or the process restarts. */
internal class DownloadSortMemory(
    private val settings: Settings,
) {
    fun read(): DownloadSort =
        settings.getStringOrNull(KEY)?.let { saved -> DownloadSort.entries.firstOrNull { it.name == saved } }
            ?: DownloadSort.Added

    fun write(sort: DownloadSort) = settings.putString(KEY, sort.name)

    private companion object {
        const val KEY = "downloads.sort.v1"
    }
}
