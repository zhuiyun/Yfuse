package com.yfuse.feature.player

import com.yfuse.core.data.RememberedPlaybackTrack

/**
 * Stable track identity used while an engine or physical media version is rebuilt.
 *
 * [languageOrdinal] is the track's position among the tracks of the same language it was
 * chosen from: two 国语 tracks with engine-generated labels have nothing else to tell them apart.
 */
internal data class TrackRestorePreference(
    val language: String?,
    val label: String,
    val codec: String?,
    val languageOrdinal: Int? = null,
)

internal fun EngineTrack.toRestorePreference(): TrackRestorePreference =
    TrackRestorePreference(
        language = language?.trim()?.takeIf(String::isNotEmpty),
        label = label.trim(),
        codec = codec?.trim()?.takeIf(String::isNotEmpty),
    )

/** [toRestorePreference] with the ordinal among same-language siblings in this list. */
internal fun List<EngineTrack>.restorePreferenceFor(track: EngineTrack): TrackRestorePreference =
    track.toRestorePreference().copy(languageOrdinal = languageOrdinalOf(track))

private fun List<EngineTrack>.languageOrdinalOf(track: EngineTrack): Int? {
    val language = track.language?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val siblings = filter { it.language.equals(language, ignoreCase = true) }
    return siblings.indexOfFirst { it.id == track.id }.takeIf { it >= 0 }
}

internal fun EngineTrack.toRememberedPlaybackTrack(): RememberedPlaybackTrack =
    RememberedPlaybackTrack(
        language = language?.trim()?.takeIf(String::isNotEmpty),
        label = label.trim(),
        codec = codec?.trim()?.takeIf(String::isNotEmpty),
    )

/** [toRememberedPlaybackTrack] with the ordinal among same-language siblings in this list. */
internal fun List<EngineTrack>.rememberedTrackFor(track: EngineTrack): RememberedPlaybackTrack =
    track.toRememberedPlaybackTrack().copy(languageOrdinal = languageOrdinalOf(track))

internal fun RememberedPlaybackTrack.toRestorePreference(): TrackRestorePreference =
    TrackRestorePreference(
        language = language?.trim()?.takeIf(String::isNotEmpty),
        label = label.trim(),
        codec = codec?.trim()?.takeIf(String::isNotEmpty),
        languageOrdinal = languageOrdinal,
    )

/**
 * Track ids are engine- and version-local. Prefer language, then narrow equal-language
 * candidates with label, codec, then the remembered ordinal; a language with several
 * candidates and nothing else to go on still restores its first track rather than none,
 * because the viewer asked for that language and silence is the one answer that is wrong.
 * Without a language, only an exact label counts.
 */
internal fun List<EngineTrack>.bestRestoreMatch(preference: TrackRestorePreference): EngineTrack? {
    val languageMatches =
        preference.language
            ?.let { language ->
                filter { it.language.equals(language, ignoreCase = true) }
            }.orEmpty()
    if (languageMatches.isEmpty()) {
        return firstOrNull { it.label.equals(preference.label, ignoreCase = true) }
    }
    return languageMatches.firstOrNull { it.label.equals(preference.label, ignoreCase = true) }
        ?: preference.codec?.let { codec ->
            languageMatches.firstOrNull { it.codec.equals(codec, ignoreCase = true) }
        }
        ?: preference.languageOrdinal?.let(languageMatches::getOrNull)
        ?: languageMatches.first()
}
