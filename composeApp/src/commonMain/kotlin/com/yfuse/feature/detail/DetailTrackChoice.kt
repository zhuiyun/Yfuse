package com.yfuse.feature.detail

import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.model.MediaVersion

/**
 * One 音轨 / 字幕 chip: the language that travels, which of that language's tracks it is, and the
 * words on it.
 *
 * [ordinal] is what a language alone cannot say — a release's 简体, 繁體 and 简英双语 subtitles are
 * all `中文` — and it is null for 默认 and 关闭, which name no track.
 */
internal data class TrackChoice(
    val value: String?,
    val label: String,
    val ordinal: Int? = null,
) {
    /**
     * Whether this chip is the stored choice. Matching the language alone lit every 中文 chip at
     * once; a stored language without an ordinal is that language's first track, which is also
     * what the player opens for it.
     */
    fun isSelected(
        language: String?,
        selectedOrdinal: Int?,
    ): Boolean =
        if (ordinal == null) {
            value == language
        } else {
            value.equals(language, ignoreCase = true) && ordinal == (selectedOrdinal ?: 0)
        }
}

internal fun audioTrackChoices(version: MediaVersion): List<TrackChoice> {
    val ordinals = version.audioTracks.sameLanguageOrdinals({ it.language }, { it.external == true })
    return buildList {
        add(TrackChoice(null, "默认"))
        // A track the server tagged with no language is unreachable — language is the only
        // handle the player has on it — so it is not offered rather than offered and ignored.
        version.audioTracks.forEachIndexed { index, track ->
            val language = track.language ?: return@forEachIndexed
            add(TrackChoice(language, track.choiceLabel, ordinals[index]))
        }
    }
}

internal fun subtitleTrackChoices(version: MediaVersion): List<TrackChoice> {
    val ordinals = version.subtitleTracks.sameLanguageOrdinals({ it.language }, { it.external })
    return buildList {
        add(TrackChoice(null, "默认"))
        add(TrackChoice(PlaybackTrackRequest.SUBTITLES_OFF, "关闭"))
        version.subtitleTracks.forEachIndexed { index, track ->
            val language = track.language ?: return@forEachIndexed
            add(TrackChoice(language, track.label, ordinals[index]))
        }
    }
}

/**
 * Each track's place among this file's tracks of the same language, counted the way an engine
 * lists them once it has opened the file: the container's own streams first, then the sidecars
 * it attaches. Null for a track with no language, which no choice can name.
 */
internal fun <T> List<T>.sameLanguageOrdinals(
    language: (T) -> String?,
    external: (T) -> Boolean,
): List<Int?> {
    val ordinals = arrayOfNulls<Int>(size)
    val counted = mutableMapOf<String, Int>()
    indices.sortedBy { external(this[it]) }.forEach { index ->
        val key = language(this[index])?.lowercase() ?: return@forEach
        val ordinal = counted[key] ?: 0
        ordinals[index] = ordinal
        counted[key] = ordinal + 1
    }
    return ordinals.toList()
}

/**
 * The 音轨 / 字幕 choice as the player receives it, for the file 播放 opens. One builder, so the
 * request 播放 leaves and the one a source warm-up prepares for cannot drift apart.
 */
internal fun DetailState.requestedTracks(): PlaybackTrackRequest.Tracks {
    val versions = playTarget?.versions.orEmpty()
    val version = versions.firstOrNull { it.id == selectedVersionId } ?: versions.firstOrNull()
    return PlaybackTrackRequest.Tracks(
        audioLanguage = preferredAudioLanguage,
        subtitleLanguage = preferredSubtitleLanguage,
        audioHint =
            version?.audioTracks?.trackHint(
                language = preferredAudioLanguage,
                ordinal = preferredAudioOrdinal,
                languageOf = { it.language },
                external = { it.external == true },
                title = { it.title },
                codec = { it.codec },
            ),
        subtitleHint =
            version?.subtitleTracks?.trackHint(
                language = preferredSubtitleLanguage,
                ordinal = preferredSubtitleOrdinal,
                languageOf = { it.language },
                external = { it.external },
                title = { it.title },
                codec = { it.codec },
            ),
    )
}

/**
 * The picked track's title, codec and place among its language, for the player to choose with.
 * Null while the language names one track: there is nothing to tell apart, and the request
 * stays the plain language it always was.
 */
private fun <T> List<T>.trackHint(
    language: String?,
    ordinal: Int?,
    languageOf: (T) -> String?,
    external: (T) -> Boolean,
    title: (T) -> String?,
    codec: (T) -> String?,
): PlaybackTrackRequest.TrackHint? {
    if (language == null || language == PlaybackTrackRequest.SUBTITLES_OFF) return null
    val siblings = indices.filter { languageOf(this[it]).equals(language, ignoreCase = true) }
    if (siblings.size < 2) return null
    val ordinals = sameLanguageOrdinals(languageOf, external)
    val wanted = ordinal ?: 0
    val picked = siblings.firstOrNull { ordinals[it] == wanted }?.let { this[it] } ?: return null
    return PlaybackTrackRequest.TrackHint(
        label = title(picked),
        codec = codec(picked),
        languageOrdinal = wanted,
    )
}
