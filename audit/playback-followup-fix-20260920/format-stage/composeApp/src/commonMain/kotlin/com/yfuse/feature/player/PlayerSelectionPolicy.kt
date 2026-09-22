package com.yfuse.feature.player

import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackPreference
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.matchingPreference

/** Resolves the engine track that best answers to a preferred display language. */
internal fun List<EngineTrack>.matchingLanguage(language: String): String? {
    if (language.isBlank()) return null
    return map { YTrack(it.id, YTrackType.Audio, it.label, it.language, it.codec, it.selected) }
        .matchingPreference(YTrackPreference(language = language))
        ?.id
}

/** Best remaining physical file after every engine rejected the selected version. */
internal fun PlayerMediaItem.nextFallbackVersionId(tried: Set<String>): String? =
    versions
        .sortedWith(
            compareByDescending<PlayerMediaVersion> { it.sourceWidth ?: 0 }
                .thenByDescending { it.sourceBitrateBps ?: 0 },
        ).firstOrNull { it.id !in tried }
        ?.id

/** Manual selection starts a new recovery budget; automatic recovery preserves history. */
internal fun updatedVersionAttempts(
    tried: Set<String>,
    selected: String,
    automaticRecovery: Boolean,
): Set<String> = if (automaticRecovery) tried + selected else setOf(selected)
