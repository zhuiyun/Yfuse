package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackFailureKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Terminal backend used when the selected native binary provably lacks a required feature.
 *
 * This is not recorded as a decoder/container quirk: installing the correct AAR changes the answer,
 * so poisoning YCore's week-long failure memory would keep a repaired build from trying mpv later.
 */
internal class MissingNativeCapabilityVideoEngine(
    message: String,
    startIndex: Int,
    itemCount: Int,
    startPositionMs: Long,
) : VideoEngine {
    private val failureMessage = message
    private val mutableState =
        MutableStateFlow(
            PlaybackState(
                playing = false,
                buffering = false,
                positionMs = startPositionMs.coerceAtLeast(0L),
                currentIndex = startIndex.coerceAtLeast(0),
                itemCount = itemCount.coerceAtLeast(1),
                error = failureMessage,
                errorKind = PlaybackFailureKind.Unknown,
                fallbacksExhausted = true,
                diagnostics =
                    PlaybackDiagnostics(
                        engine = "libmpv",
                        decoder = "native capability missing",
                        playMethod = "原盘直读不可用",
                        fallbackReason = failureMessage,
                    ),
            ),
        )
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    override val playbackRequested: Boolean get() = false

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) {
        mutableState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    override fun setSpeed(speed: Float) {
        mutableState.update { it.copy(speed = speed.coerceAtLeast(0.1f)) }
    }

    override fun selectAudioTrack(id: String) = Unit

    override fun selectSubtitleTrack(id: String) = Unit

    override fun selectItem(index: Int) {
        if (index !in 0 until mutableState.value.itemCount) return
        mutableState.update {
            it.copy(
                currentIndex = index,
                positionMs = 0L,
                error = failureMessage,
                errorKind = PlaybackFailureKind.Unknown,
                fallbacksExhausted = true,
            )
        }
    }

    override fun currentPositionMs(): Long = mutableState.value.positionMs

    override fun retry() {
        mutableState.update {
            it.copy(
                error = failureMessage,
                errorKind = PlaybackFailureKind.Unknown,
                buffering = false,
                fallbacksExhausted = true,
            )
        }
    }

    override fun release() = Unit
}
