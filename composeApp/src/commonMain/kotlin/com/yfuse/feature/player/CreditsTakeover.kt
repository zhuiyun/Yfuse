package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType

/** 片尾接管: how large the picture stays, in its corner, while the credits run beside the next episode. */
internal const val CREDITS_PICTURE_SCALE = 0.5f

/** Where 片尾接管 stands at a playback sample. */
internal enum class CreditsTakeoverPhase {
    /** Full picture, nothing offered. */
    Off,

    /** The picture in its corner and the next-episode card beside it. */
    Card,

    /**
     * The file's last seconds: the picture stays in its corner, and the card gives way to the
     * ordinary next-up countdown, which owns that moment with its ring and its 取消.
     */
    Countdown,
}

/** The credits the takeover works from: the earliest credits marker inside the file, or null. */
internal fun creditsSegment(
    segments: List<PlaybackSegment>,
    durationMs: Long,
): PlaybackSegment? =
    segments
        .filter {
            it.type == PlaybackSegmentType.Credits &&
                it.startMs > 0L &&
                (durationMs <= 0L || it.startMs < durationMs)
        }.minByOrNull { it.startMs }

/**
 * 片尾接管下一集 — from the credits marker to the end of the credits (the file's, when the marker has
 * no end of its own), with a next episode to offer.
 *
 * Credits no longer than the ordinary card's own [NEXT_UP_WINDOW_MS] are left to that card, and a
 * credits marker that ends early — a scene after the credits — gives the picture back when it does.
 * [blocked] is everything that is not the timeline's: a watch-together guest, a cast, the lock, an
 * automatic skip counting down, 看完片尾 or 取消 already chosen for this episode.
 */
internal fun creditsTakeoverPhase(
    positionMs: Long,
    durationMs: Long,
    credits: PlaybackSegment?,
    hasNext: Boolean,
    finished: Boolean,
    blocked: Boolean,
): CreditsTakeoverPhase {
    if (credits == null || !hasNext || finished || blocked || durationMs <= 0L) return CreditsTakeoverPhase.Off
    val end = (credits.endMs ?: durationMs).coerceAtMost(durationMs)
    if (end - credits.startMs <= NEXT_UP_WINDOW_MS) return CreditsTakeoverPhase.Off
    if (positionMs !in credits.startMs until end) return CreditsTakeoverPhase.Off
    val lastSeconds = durationMs - positionMs <= NEXT_UP_WINDOW_MS
    return if (lastSeconds) CreditsTakeoverPhase.Countdown else CreditsTakeoverPhase.Card
}
