package com.yfuse.feature.player

import com.yfuse.core.model.languageDisplayName

/** 没听清: how far a long press on ⟲10 goes back — the same ten seconds the tap does. */
internal const val MISSED_LINE_REWIND_MS = 10_000L

/**
 * A press this close to the start has nothing behind it worth replaying with subtitles on: the
 * rewind still happens and the subtitles stay as they are.
 */
internal const val MISSED_LINE_MIN_REPLAY_MS = 2_000L

/**
 * How far behind the press the playhead has to be before the rewind counts as having landed.
 *
 * The seek is asynchronous: for a sample or two after the press the playhead still reads where
 * it was, or a little past it, and a restore judged on those samples would put the subtitles
 * away before the line had been replayed at all.
 */
internal const val MISSED_LINE_REWIND_ARM_MS = 1_000L

/**
 * A subtitle 没听清 turned on for the replay, and what goes back once the line has been heard.
 *
 * Nothing here is remembered anywhere — not the series, not the preferences, not the restore state
 * a handover carries. It is the moment's, and it ends with the moment.
 */
internal data class SubtitlePeek(
    /** The track shown for the replay. */
    val trackId: String,
    /** The primary subtitle that was up before, exactly: [EngineTrack.OFF] when none was. */
    val restoreTrackId: String,
    /** Where the press happened. Once playback passes it again, the line has been heard. */
    val untilMs: Long,
    /** The playhead has been seen behind [untilMs], so the rewind has landed. */
    val rewound: Boolean = false,
    /** The engine has reported [trackId] selected; a different selection after this is someone's choice. */
    val confirmed: Boolean = false,
)

/** What a long press on ⟲10 does: where it goes, the subtitle it turns on, and what the HUD says. */
internal data class MissedLineRewind(
    val targetMs: Long,
    /** The peek to run from now on — a new one, the running one stretched, or null for none. */
    val peek: SubtitlePeek?,
    val message: String,
)

/** What a running peek does with a new playback sample. */
internal sealed interface SubtitlePeekStep {
    /** Still replaying: carry on with [peek]. */
    data class Hold(
        val peek: SubtitlePeek,
    ) : SubtitlePeekStep

    /** The line has been heard again: put [trackId] back, [EngineTrack.OFF] included. */
    data class Restore(
        val trackId: String,
    ) : SubtitlePeekStep

    /** The subtitles have changed hands since — a pick, a new engine — so they are left alone. */
    data object Release : SubtitlePeekStep
}

/**
 * 没听清 — rewind ten seconds and, when the subtitles are off, turn one on until the playhead is
 * back where the press happened.
 *
 * Subtitles already up are left alone: the rewind is all the viewer needs. A second press while a
 * peek is running rewinds again and keeps the subtitle up until the later of the two positions.
 * [subtitlesAllowed] is false where the subtitle is not this device's to change (casting).
 */
internal fun missedLineRewind(
    positionMs: Long,
    subtitleTracks: List<EngineTrack>,
    audioTracks: List<EngineTrack>,
    secondarySubtitleTrackId: String?,
    running: SubtitlePeek?,
    subtitlesAllowed: Boolean,
): MissedLineRewind {
    val position = positionMs.coerceAtLeast(0L)
    val targetMs = (position - MISSED_LINE_REWIND_MS).coerceAtLeast(0L)
    if (running != null) {
        return MissedLineRewind(
            targetMs = targetMs,
            peek = running.copy(untilMs = maxOf(running.untilMs, position)),
            message = MISSED_LINE_REWIND_ONLY,
        )
    }
    val selected = subtitleTracks.firstOrNull { it.selected }
    val subtitlesUp = (selected != null && !selected.isForcedSubtitle()) || secondarySubtitleTrackId != null
    if (!subtitlesAllowed || subtitlesUp || position < MISSED_LINE_MIN_REPLAY_MS) {
        return MissedLineRewind(targetMs, peek = null, message = MISSED_LINE_REWIND_ONLY)
    }
    val track =
        missedLineSubtitleTrack(subtitleTracks, audioTracks)
            ?: return MissedLineRewind(targetMs, peek = null, message = "没听清：倒回 10 秒（没有可用的字幕）")
    return MissedLineRewind(
        targetMs = targetMs,
        peek =
            SubtitlePeek(
                trackId = track.id,
                restoreTrackId = selected?.id ?: EngineTrack.OFF,
                untilMs = position,
            ),
        message = "没听清：倒回 10 秒，临时打开字幕",
    )
}

private const val MISSED_LINE_REWIND_ONLY = "没听清：倒回 10 秒"

/**
 * The subtitle 没听清 turns on: one in the language being spoken — the line that was missed is in
 * it — or else the first the file lists. A forced track, which only covers foreign-language
 * dialogue, is the last resort in either group: it would say nothing about the line just missed.
 */
internal fun missedLineSubtitleTrack(
    subtitleTracks: List<EngineTrack>,
    audioTracks: List<EngineTrack>,
): EngineTrack? {
    val candidates = subtitleTracks.sortedBy { it.isForcedSubtitle() }
    val spoken = subtitleLanguageKey(audioTracks.firstOrNull { it.selected }?.language)
    return spoken?.let { language -> candidates.firstOrNull { subtitleLanguageKey(it.language) == language } }
        ?: candidates.firstOrNull()
}

/**
 * Where a running peek stands after a playback sample, given the subtitle tracks as that sample
 * reports them.
 */
internal fun SubtitlePeek.step(
    positionMs: Long,
    subtitleTracks: List<EngineTrack>,
): SubtitlePeekStep {
    val selectedId = subtitleTracks.firstOrNull { it.selected }?.id ?: EngineTrack.OFF
    val confirmedNow = confirmed || selectedId == trackId
    if (confirmedNow && selectedId != trackId) return SubtitlePeekStep.Release
    val rewoundNow = rewound || positionMs <= untilMs - MISSED_LINE_REWIND_ARM_MS
    // A rewind that never landed — refused or dropped — still ends the peek once the line would
    // have gone by twice over, rather than leaving the subtitle up for the rest of the film.
    val heard =
        if (rewoundNow) positionMs >= untilMs else positionMs >= untilMs + MISSED_LINE_REWIND_MS
    if (!heard) return SubtitlePeekStep.Hold(copy(rewound = rewoundNow, confirmed = confirmedNow))
    val restorable = restoreTrackId == EngineTrack.OFF || subtitleTracks.any { it.id == restoreTrackId }
    return if (restorable) SubtitlePeekStep.Restore(restoreTrackId) else SubtitlePeekStep.Release
}

/**
 * The primary subtitle a handover should carry over: the viewer's own choice — the one a running
 * peek set aside — rather than the subtitle 没听清 is showing for the moment.
 */
internal fun viewerSubtitleChoice(
    subtitleTracks: List<EngineTrack>,
    peek: SubtitlePeek?,
): EngineTrack? =
    if (peek != null) {
        subtitleTracks.firstOrNull { it.id == peek.restoreTrackId }
    } else {
        subtitleTracks.firstOrNull { it.selected }
    }

private fun EngineTrack.isForcedSubtitle(): Boolean =
    label.contains("forced", ignoreCase = true) ||
        label.contains("强制")

/**
 * One key per spoken language, whatever the file tagged it with: `eng`, `en` and `English` all
 * read as 英语. Cantonese dialogue is subtitled in written Chinese, so it matches Chinese tracks.
 */
private fun subtitleLanguageKey(language: String?): String? {
    val value =
        language
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')
            ?.substringBefore('-')
            ?.takeIf { it.isNotEmpty() && it != "und" && it != "unknown" }
            ?: return null
    return languageDisplayName(
        when (value) {
            "english" -> "en"
            "chinese", "mandarin", "cantonese", "cmn", "yue", "chs", "cht", "国语", "普通话", "粤语", "中文" -> "zh"
            "japanese" -> "ja"
            "korean" -> "ko"
            "french" -> "fr"
            "german" -> "de"
            "spanish" -> "es"
            else -> value
        },
    )
}
