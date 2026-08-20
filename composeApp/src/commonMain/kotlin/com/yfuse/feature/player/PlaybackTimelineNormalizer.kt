package com.yfuse.feature.player

import kotlin.math.max

/** Canonical player position captured when a concrete Legacy engine is constructed. */
internal data class PlaybackTimelineAnchor(
    val itemIndex: Int,
    val positionMs: Long,
)

/**
 * Short-lived handoff between the Android engine factory and the Legacy -> YPlayer adapter.
 *
 * Legacy engines construct their native player before the adapter exists. Keeping the requested
 * canonical start position here lets the adapter distinguish a real media timestamp offset from
 * an engine that is merely still preparing a resume seek.
 */
internal object PlaybackTimelineAnchorRegistry {
    private val anchors = mutableMapOf<VideoEngine, PlaybackTimelineAnchor>()

    fun register(
        engine: VideoEngine,
        itemIndex: Int,
        positionMs: Long,
    ): VideoEngine {
        anchors[engine] =
            PlaybackTimelineAnchor(
                itemIndex = itemIndex.coerceAtLeast(0),
                positionMs = positionMs.coerceAtLeast(0L),
            )
        return engine
    }

    fun take(engine: VideoEngine): PlaybackTimelineAnchor? = anchors.remove(engine)
}

/**
 * Converts backend-local media time into the single product timeline used by controls and handover.
 *
 * Exo, mpv and MDK do not promise the same timestamp origin. A file can therefore expose the same
 * frame as 00:30.000 in one backend and 00:31.800 in another. The first stable sample establishes
 * an origin offset against the position YCore requested. From then on the conversion is symmetric:
 * reads subtract that origin and seeks add it back.
 *
 * The origin is recalibrated when the active item or source kind changes. That matters for direct
 * play -> server transcode because the replacement stream can have a different timestamp origin
 * even though the viewer must remain on the same canonical position.
 */
internal class PlaybackTimelineNormalizer(
    anchor: PlaybackTimelineAnchor,
) {
    private var itemIndex = anchor.itemIndex
    private var sourceTranscoding: Boolean? = null
    private var canonicalAnchorMs = anchor.positionMs
    private var backendAnchorMs: Long? = null
    private var pendingCanonicalPositionMs = anchor.positionMs
    private var lastCanonicalPositionMs = anchor.positionMs

    fun normalize(state: PlaybackState): PlaybackState {
        reconcileSource(state)
        maybeCalibrate(state)
        val position = normalizedPosition(state.positionMs, state.durationMs, state.ended)
        lastCanonicalPositionMs = position
        val buffered =
            max(
                position,
                normalizedPosition(
                    rawPositionMs = state.bufferedPositionMs,
                    durationMs = state.durationMs,
                    ended = state.ended,
                ),
            )
        return state.copy(
            positionMs = position,
            bufferedPositionMs = buffered,
        )
    }

    fun currentPositionMs(
        rawPositionMs: Long,
        state: PlaybackState,
    ): Long {
        reconcileSource(state)
        maybeCalibrate(state.copy(positionMs = rawPositionMs.coerceAtLeast(0L)))
        return normalizedPosition(
            rawPositionMs = rawPositionMs,
            durationMs = state.durationMs,
            ended = state.ended,
        ).also { lastCanonicalPositionMs = it }
    }

    fun backendPositionForSeek(
        canonicalPositionMs: Long,
        state: PlaybackState,
    ): Long {
        reconcileSource(state)
        val canonical = canonicalPositionMs.coerceAtLeast(0L)
        pendingCanonicalPositionMs = canonical
        lastCanonicalPositionMs = canonical
        val backendAnchor = backendAnchorMs
        if (backendAnchor == null) {
            canonicalAnchorMs = canonical
            return canonical
        }
        val offsetMs = canonicalAnchorMs - backendAnchor
        return (canonical - offsetMs).coerceAtLeast(0L)
    }

    fun selectItem(index: Int) {
        reset(
            newIndex = index.coerceAtLeast(0),
            canonicalPositionMs = 0L,
            transcoding = null,
        )
    }

    private fun reconcileSource(state: PlaybackState) {
        if (state.currentIndex != itemIndex) {
            reset(
                newIndex = state.currentIndex.coerceAtLeast(0),
                canonicalPositionMs = 0L,
                transcoding = state.transcoding,
            )
            return
        }
        val previousTranscoding = sourceTranscoding
        if (previousTranscoding == null) {
            sourceTranscoding = state.transcoding
        } else if (previousTranscoding != state.transcoding) {
            sourceTranscoding = state.transcoding
            backendAnchorMs = null
            canonicalAnchorMs = lastCanonicalPositionMs
            pendingCanonicalPositionMs = lastCanonicalPositionMs
        }
    }

    private fun maybeCalibrate(state: PlaybackState) {
        if (backendAnchorMs != null) return
        if (state.error != null || state.durationMs <= 0L || state.buffering) return

        val rawPosition = state.positionMs.coerceAtLeast(0L)
        val requestedPosition = pendingCanonicalPositionMs.coerceAtLeast(0L)

        // A resumed engine often reports zero briefly before its deferred seek is applied. Do not
        // mistake that preparation sample for a giant timestamp-origin offset.
        if (
            requestedPosition >= RESUME_POSITION_GUARD_MS &&
            rawPosition + RESUME_BEHIND_TOLERANCE_MS < requestedPosition
        ) {
            return
        }

        // At a fresh 0 ms start, wait for either actual output or a moving media clock. This avoids
        // locking the origin to a placeholder zero emitted while the backend is merely prepared.
        if (
            requestedPosition == 0L &&
            rawPosition == 0L &&
            !state.playing &&
            state.diagnostics.videoReadiness != PlaybackOutputReadiness.Rendering
        ) {
            return
        }

        backendAnchorMs = rawPosition
        canonicalAnchorMs = requestedPosition
    }

    private fun normalizedPosition(
        rawPositionMs: Long,
        durationMs: Long,
        ended: Boolean,
    ): Long {
        if (ended && durationMs > 0L) return durationMs
        val backendAnchor = backendAnchorMs ?: return clamp(pendingCanonicalPositionMs, durationMs)
        val deltaMs = rawPositionMs.coerceAtLeast(0L) - backendAnchor
        return clamp(canonicalAnchorMs + deltaMs, durationMs)
    }

    private fun reset(
        newIndex: Int,
        canonicalPositionMs: Long,
        transcoding: Boolean?,
    ) {
        itemIndex = newIndex
        sourceTranscoding = transcoding
        canonicalAnchorMs = canonicalPositionMs.coerceAtLeast(0L)
        backendAnchorMs = null
        pendingCanonicalPositionMs = canonicalAnchorMs
        lastCanonicalPositionMs = canonicalAnchorMs
    }

    private fun clamp(
        value: Long,
        durationMs: Long,
    ): Long {
        val nonNegative = value.coerceAtLeast(0L)
        return if (durationMs > 0L) nonNegative.coerceAtMost(durationMs) else nonNegative
    }
}

private const val RESUME_POSITION_GUARD_MS = 1_000L
private const val RESUME_BEHIND_TOLERANCE_MS = 1_000L
