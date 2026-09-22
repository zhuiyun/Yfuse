package com.yfuse.feature.player

/** Recover immediately on failure; optional inspection waits for real output and a useful buffer. */
internal fun backgroundPlaybackProbeAllowed(state: PlaybackState): Boolean =
    state.error != null ||
        (
            !state.buffering &&
                state.playing &&
                state.speed.isFinite() &&
                state.speed > 0f &&
                (
                    state.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering ||
                        state.diagnostics.effectiveAudioReadiness == PlaybackOutputReadiness.Rendering
                ) &&
                (state.bufferedPositionMs - state.positionMs).coerceAtLeast(0L) / state.speed >= 8_000L
        )
