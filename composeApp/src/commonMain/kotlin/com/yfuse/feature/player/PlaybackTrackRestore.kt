package com.yfuse.feature.player

/** Stable track identity used while an engine or physical media version is rebuilt. */
internal data class TrackRestorePreference(
    val language: String?,
    val label: String,
    val codec: String?,
)

internal fun EngineTrack.toRestorePreference(): TrackRestorePreference =
    TrackRestorePreference(
        language = language?.trim()?.takeIf(String::isNotEmpty),
        label = label.trim(),
        codec = codec?.trim()?.takeIf(String::isNotEmpty),
    )

/**
 * Track ids are engine- and version-local. Prefer language, then narrow equal-language
 * candidates with label/codec; fall back to exact metadata only when language is absent.
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
        ?: languageMatches.singleOrNull()
}
