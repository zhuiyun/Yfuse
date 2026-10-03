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
