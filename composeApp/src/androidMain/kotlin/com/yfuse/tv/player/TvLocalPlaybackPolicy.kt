package com.yfuse.tv.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerMediaVersion

/**
 * TV playback is client-owned: YCore may hand off to another local backend, never to server decode.
 * Removing every transcode address makes that invariant structural instead of relying on each
 * current and future engine remembering a TV-specific flag.
 */
internal fun List<PlayerMediaItem>.withoutServerTranscodeForTv(): List<PlayerMediaItem> =
    map { it.withoutServerTranscodeForTv() }

internal fun PlayerMediaItem.withoutServerTranscodeForTv(): PlayerMediaItem {
    val directVersions =
        versions
            .filter { it.playMethod != PlaybackMethod.Transcode && it.url.isNotBlank() }
            .map { it.withoutServerTranscodeForTv() }
    val selectedDirectVersion =
        directVersions.firstOrNull { it.id == versionId }
            ?: directVersions.firstOrNull()
    val itemAlreadyDirect = playMethod != PlaybackMethod.Transcode && url.isNotBlank()
    val retainedVersionId =
        versionId?.takeIf { selectedId -> directVersions.any { it.id == selectedId } }
    return copy(
        url = if (itemAlreadyDirect) url else selectedDirectVersion?.url.orEmpty(),
        transcodeUrl = "",
        fallbackTranscodeUrl = "",
        versions = directVersions,
        versionId = if (itemAlreadyDirect) retainedVersionId else selectedDirectVersion?.id,
        playMethod =
            if (itemAlreadyDirect) {
                playMethod
            } else {
                selectedDirectVersion?.playMethod ?: playMethod
            },
        serverTranscodeSupported = false,
        forcedTranscodeReason = null,
        serverFallbacks = serverFallbacks.withoutServerTranscodeForTv(),
    )
}

private fun PlayerMediaVersion.withoutServerTranscodeForTv(): PlayerMediaVersion =
    copy(
        transcodeUrl = "",
        fallbackTranscodeUrl = "",
        serverTranscodeSupported = false,
    )
