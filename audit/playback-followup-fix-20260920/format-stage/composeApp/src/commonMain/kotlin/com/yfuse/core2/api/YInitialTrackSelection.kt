package com.yfuse.core2.api

import com.yfuse.core.model.languageDisplayName

/** Stable across server stream indexes and platform/enhanced demux track numbering. */
data class YTrackPreference(
    val language: String? = null,
    val label: String? = null,
    val codec: String? = null,
    val languageOrdinal: Int? = null,
    /** Trusted ordinal within one discovered track type; never a provider's global stream id. */
    val trackOrdinal: Int? = null,
)

data class YInitialTrackSelection(
    val audio: YTrackPreference? = null,
    val subtitle: YTrackPreference? = null,
    val subtitlesDisabled: Boolean = false,
) {
    fun orNull(): YInitialTrackSelection? = takeIf { audio != null || subtitle != null || subtitlesDisabled }
}

/** Resolve intent only from discovered container tracks; never guess a server index. */
fun List<YTrack>.matchingPreference(preference: YTrackPreference?): YTrack? {
    if (preference == null) return null
    val language = preference.language?.trim()?.takeIf(String::isNotEmpty)
    val wantedLanguage = language?.let(::trackLanguageName)
    preference.trackOrdinal?.let { index ->
        getOrNull(index)
            ?.takeIf { track ->
                (language == null || trackLanguageName(track.language).equals(wantedLanguage, true)) &&
                    (preference.label.isNullOrBlank() || track.label.equals(preference.label, true)) &&
                    (preference.codec.isNullOrBlank() || track.codec.equals(preference.codec, true))
            }?.let { return it }
    }
    val languageMatches =
        if (language == null) {
            emptyList()
        } else {
            filter { track ->
                if (!track.language.isNullOrBlank()) {
                    track.language.equals(language, ignoreCase = true) ||
                        trackLanguageName(track.language).equals(wantedLanguage, ignoreCase = true)
                } else {
                    track.label.equals(language, ignoreCase = true) ||
                        track.label.split(' ', '-', '/', ',', '(', ')').any { token ->
                            token.equals(language, ignoreCase = true) ||
                                trackLanguageName(token).equals(wantedLanguage, ignoreCase = true)
                        }
                }
            }
        }
    val label = preference.label?.takeIf(String::isNotBlank)
    if (languageMatches.isEmpty()) {
        val candidates = filter { language == null || it.language.isNullOrBlank() }
        return label?.let { wanted -> candidates.firstOrNull { it.label.equals(wanted, true) } }
            ?: preference.codec?.takeIf { language == null }?.let { codec ->
                candidates.filter { it.codec.equals(codec, true) }.singleOrNull()
            }
    }
    return label?.let { wanted -> languageMatches.filter { it.label.equals(wanted, true) }.singleOrNull() }
        ?: preference.languageOrdinal?.let(languageMatches::getOrNull)
        ?: preference.codec?.let { wanted -> languageMatches.filter { it.codec.equals(wanted, true) }.singleOrNull() }
        ?: languageMatches.first()
}

private fun trackLanguageName(language: String?): String? {
    val value =
        language
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')
            ?.substringBefore('-') ?: return null
    return languageDisplayName(
        when (value) {
            "english" -> "en"
            "chinese", "mandarin", "国语", "普通话" -> "zh"
            "japanese" -> "ja"
            "french" -> "fr"
            "german" -> "de"
            "spanish" -> "es"
            else -> value
        },
    )
}

fun YTrack.preferenceIn(tracks: List<YTrack>): YTrackPreference =
    YTrackPreference(
        language = language,
        label = label,
        codec = codec,
        languageOrdinal =
            tracks
                .filter { trackLanguageName(it.language).equals(trackLanguageName(language), ignoreCase = true) }
                .indexOfFirst { it.id == id }
                .takeIf { it >= 0 },
    )

/** A side-effect-free guard used before any route teardown, including before initial discovery. */
fun YPlayerState.trackSelectionSkipReason(
    type: YTrackType,
    id: String,
): String? {
    val tracks = if (type == YTrackType.Audio) audioTracks else subtitleTracks
    if (type == YTrackType.Subtitle && id == "off") {
        return if (tracks.none { it.selected }) "already_disabled" else null
    }
    val target = tracks.firstOrNull { it.id == id } ?: return "track_not_discovered"
    return if (target.selected) "already_selected" else null
}
