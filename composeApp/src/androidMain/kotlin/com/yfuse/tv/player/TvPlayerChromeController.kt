package com.yfuse.tv.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Android TV owner for playback-chrome commands and externally observable chrome truth. */
internal class TvPlayerChromeController : TvPlayerChromeBridge {
    private val mutableState = MutableStateFlow(TvPlayerChromeState())
    private val mutableCommands = MutableSharedFlow<TvPlayerChromeCommand>(extraBufferCapacity = 16)
    private var commandSequence = 0L

    override val state: StateFlow<TvPlayerChromeState> = mutableState.asStateFlow()
    override val commands: Flow<TvPlayerChromeCommand> = mutableCommands.asSharedFlow()

    fun showControls() {
        mutableState.update {
            it.copy(
                // Optimistic, so the next key of a burst already finds the chrome up. Without an
                // attached surface nothing would ever publish the real layer back over the guess.
                layer =
                    if (it.attached && it.layer == TvPlayerChromeLayer.Hidden) {
                        TvPlayerChromeLayer.Controls
                    } else {
                        it.layer
                    },
                interactionRevision = it.interactionRevision + 1,
            )
        }
        emit(TvPlayerChromeCommandType.ShowControls)
    }

    fun hideControls() {
        mutableState.update {
            it.copy(
                layer = TvPlayerChromeLayer.Hidden,
                panel = null,
                controlsHaveFocus = false,
                interactionRevision = it.interactionRevision + 1,
            )
        }
        emit(TvPlayerChromeCommandType.HideControls)
    }

    fun closeTop() {
        mutableState.update { it.copy(interactionRevision = it.interactionRevision + 1) }
        emit(TvPlayerChromeCommandType.CloseTop)
    }

    fun openTracks() {
        mutableState.update { it.copy(interactionRevision = it.interactionRevision + 1) }
        emit(TvPlayerChromeCommandType.OpenTracks)
    }

    fun openInfo() {
        mutableState.update { it.copy(interactionRevision = it.interactionRevision + 1) }
        emit(TvPlayerChromeCommandType.OpenInfo)
    }

    fun updateSeekPreview(positionMs: Long) {
        mutableState.update {
            it.copy(
                seeking = true,
                seekTargetMs = positionMs.coerceAtLeast(0L),
                interactionRevision = it.interactionRevision + 1,
            )
        }
    }

    fun finishSeekPreview() {
        mutableState.update {
            it.copy(
                seeking = false,
                seekTargetMs = null,
                interactionRevision = it.interactionRevision + 1,
            )
        }
    }

    override fun publishUiState(
        layer: TvPlayerChromeLayer,
        panel: TvPlayerChromePanel?,
        controlsHaveFocus: Boolean,
    ) {
        mutableState.update {
            it.copy(
                layer = layer,
                panel = panel,
                controlsHaveFocus = controlsHaveFocus,
                attached = true,
            )
        }
    }

    /**
     * Back to "no control surface": the preparation screen, or the controls leaving composition.
     * The remote's own seek preview is left alone; it ends with the key's release.
     */
    override fun detach() {
        mutableState.update {
            it.copy(
                layer = TvPlayerChromeLayer.Hidden,
                panel = null,
                controlsHaveFocus = false,
                attached = false,
            )
        }
    }

    private fun emit(type: TvPlayerChromeCommandType) {
        commandSequence++
        mutableCommands.tryEmit(TvPlayerChromeCommand(commandSequence, type))
    }
}
