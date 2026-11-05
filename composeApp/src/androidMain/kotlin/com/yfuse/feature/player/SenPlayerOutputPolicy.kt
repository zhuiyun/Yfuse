package com.yfuse.feature.player

internal const val SENPLAYER_PICTURE_COVER_MS = 3_000L

/** Native output counters differ between embedded MediaCodec and software/GPU output. */
internal class SenPlayerVideoReadinessGate {
    private var source = 0L
    private var draws = OutputCounter()
    private var frames = OutputCounter()
    private var rendered = false

    fun reset(
        sourceId: Long = 0L,
        draws: Long = 0L,
        surfaceFrames: Long = 0L,
    ) {
        source = sourceId
        this.draws = OutputCounter(draws)
        frames = OutputCounter(surfaceFrames)
        rendered = false
    }

    fun observe(
        sourceId: Long,
        draws: Long,
        width: Int,
        height: Int,
        surfaceReady: Boolean,
        surfaceFrames: Long = 0L,
        changingPicture: Boolean = false,
    ): Boolean {
        if (sourceId == 0L || sourceId != source || !surfaceReady || width <= 0 || height <= 0) return false
        val drawAdvanced = this.draws.observe(draws)
        val frameAdvanced = frames.observe(surfaceFrames)
        if (this.draws.resetObserved || frames.resetObserved) rendered = false
        // Decode counts, playback position and a successful copy of an unchanged buffer are
        // deliberately absent: none of those proves that this Surface received a new picture.
        if (drawAdvanced || frameAdvanced || changingPicture) rendered = true
        return rendered
    }

    private class OutputCounter(
        initial: Long = 0L,
    ) {
        private var baseline = initial.coerceAtLeast(0)
        private var last = baseline
        var resetObserved = false
            private set

        fun observe(value: Long): Boolean {
            val current = value.coerceAtLeast(0)
            resetObserved = current < last
            if (resetObserved) baseline = 0
            last = current
            return current > baseline
        }
    }
}

internal data class SenPlayerOutputToken(
    val source: Long,
    val generation: Long,
    val surfaceEpoch: Long,
)

/** Two distinct non-flat samples, scoped to one source/seek/Surface, are output evidence. */
internal class SenPlayerPixelEvidence {
    private var token: SenPlayerOutputToken? = null
    private var previous: Long? = null
    private var changing = false

    fun observe(
        token: SenPlayerOutputToken,
        signature: Long?,
        lumaRange: Int,
    ): Boolean {
        if (this.token != token) {
            this.token = token
            previous = null
            changing = false
        }
        if (signature == null || lumaRange < 8) return changing
        if (previous != null && previous != signature) changing = true
        previous = signature
        return changing
    }
}

internal enum class SenPlayerOutputAction { None, Rebind, Warn }

/** One rebind per source/explicit seek; buffering, pauses and missing Surfaces earn no time. */
internal class SenPlayerOutputWatchdog {
    private var previousTime: Long? = null
    private var previousPosition = 0L
    private var previousFrames = 0L
    private var previousEligible = false
    private var activeMs = 0L
    private var decodedSinceReset = false
    private var rebound = false
    var warning = false
        private set

    fun observe(
        nowMs: Long,
        eligible: Boolean,
        rendered: Boolean,
        positionMs: Long,
        decodedFrames: Long,
    ): SenPlayerOutputAction {
        val elapsed = previousTime?.let { (nowMs - it).coerceIn(0, 1_000) } ?: 0
        val progressing = positionMs > previousPosition || decodedFrames > previousFrames
        if (eligible && previousEligible && progressing) {
            activeMs += elapsed
            if (decodedFrames > previousFrames) decodedSinceReset = true
        }
        previousTime = nowMs
        previousPosition = positionMs
        previousFrames = decodedFrames
        previousEligible = eligible
        if (rendered) {
            activeMs = 0
            warning = false
            return SenPlayerOutputAction.None
        }
        if (!eligible) return SenPlayerOutputAction.None
        if (!rebound && decodedSinceReset && activeMs >= 8_000) {
            rebound = true
            activeMs = 0
            return SenPlayerOutputAction.Rebind
        }
        if (!warning && activeMs >= 15_000) {
            warning = true
            return SenPlayerOutputAction.Warn
        }
        return SenPlayerOutputAction.None
    }
}

/** Count starvation after playback starts, including when first-picture evidence is missing. */
internal class SenPlayerRebufferTracker {
    private var started = false
    private var seekPending = false
    private var since: Long? = null
    private var completedMs = 0L
    private var completedLongestMs = 0L
    var events = 0
        private set
    var durationMs = 0L
        private set
    var longestMs = 0L
        private set

    fun seeking(nowMs: Long) {
        close(nowMs)
        seekPending = true
    }

    fun observe(
        nowMs: Long,
        phase: String,
        requested: Boolean,
    ) {
        if (phase == "seeking") seeking(nowMs)
        val buffering = requested && started && !seekPending && phase == "buffering"
        if (buffering && since == null) {
            since = nowMs
            events++
        } else if (!buffering) {
            close(nowMs)
        }
        if (phase == "playing" && requested) {
            started = true
            seekPending = false
        }
        val active = since?.let { (nowMs - it).coerceAtLeast(0) } ?: 0
        durationMs = completedMs + active
        longestMs = maxOf(completedLongestMs, active)
    }

    private fun close(nowMs: Long) {
        since?.let {
            val duration = (nowMs - it).coerceAtLeast(0)
            completedMs += duration
            completedLongestMs = maxOf(completedLongestMs, duration)
        }
        since = null
        durationMs = completedMs
        longestMs = completedLongestMs
    }
}
