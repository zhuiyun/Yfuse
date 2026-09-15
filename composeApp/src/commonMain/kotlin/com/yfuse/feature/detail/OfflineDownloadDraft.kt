package com.yfuse.feature.detail

import com.yfuse.core.model.MediaVersion
import com.yfuse.core.offline.OfflineBatchMode
import com.yfuse.core.offline.OfflineDownloadQuality
import com.yfuse.core.offline.OfflineDownloadSelection

/** A draft never carries a subtitle stream index across a version change. */
internal data class OfflineDownloadDraft(
    val versionId: String? = null,
    val batchMode: OfflineBatchMode = OfflineBatchMode.Current,
    val subtitleIndex: Int? = null,
    val followNewEpisodes: Boolean = false,
) {
    fun selectVersion(id: String?): OfflineDownloadDraft = copy(versionId = id, subtitleIndex = null)

    fun originalSelection(versions: List<MediaVersion>): OfflineDownloadSelection {
        val version = versions.firstOrNull { it.id == versionId } ?: versions.firstOrNull()
        val subtitle = version?.subtitleTracks?.firstOrNull { it.index == subtitleIndex && subtitleIndex != null }
        return OfflineDownloadSelection(
            batchMode = batchMode,
            mediaSourceId = version?.id,
            quality = OfflineDownloadQuality.Original,
            subtitleStreamIndex = subtitle?.index,
            subtitleCodec = subtitle?.codec,
            subtitleLanguage = subtitle?.language,
            subtitleDefault = subtitle?.default == true,
            subtitleForced = subtitle?.forced == true,
            autoDownloadNewEpisodes = followNewEpisodes,
        )
    }
}
