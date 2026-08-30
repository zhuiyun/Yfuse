package com.yfuse.tv.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The part of the playback chrome currently owning remote input. */
enum class TvPlayerChromeLayer {
    Hidden,
    Controls,
    Panel,
    Locked,
}

/** Identifies the top-most player surface so Back can dismiss exactly one level. */
enum class TvPlayerChromePanel {
    Settings,
    QuickPicker,
    Episodes,
    GestureHelp,
    WatchTogether,
    WatchChat,
    DanmakuSearch,
    DanmakuSend,
    ControlRequest,
}

/**
 * Backend-neutral TV chrome truth.
 *
 * The Activity reads this before deciding whether a D-pad key is playback input or Compose focus
 * navigation. The control composable publishes the actual layer after every UI transition; seek
 * preview is owned by the remote controller because it can advance between engine state samples.
 */
data class TvPlayerChromeState(
    val layer: TvPlayerChromeLayer = TvPlayerChromeLayer.Hidden,
    val panel: TvPlayerChromePanel? = null,
    val controlsHaveFocus: Boolean = false,
    val seeking: Boolean = false,
    val seekTargetMs: Long? = null,
    val interactionRevision: Long = 0L,
) {
    val visible: Boolean get() = layer != TvPlayerChromeLayer.Hidden
    val hasDismissibleLayer: Boolean get() = visible
}

enum class TvPlayerChromeCommandType {
    ShowControls,
    HideControls,
    CloseTop,
}

data class TvPlayerChromeCommand(
    val sequence: Long,
    val type: TvPlayerChromeCommandType,
)

/**
 * Small common boundary between Android TV key dispatch and the shared Compose player controls.
 * It deliberately carries no playback-engine operation, URL, or server fallback policy.
 */
interface TvPlayerChromeBridge {
    val state: StateFlow<TvPlayerChromeState>
    val commands: Flow<TvPlayerChromeCommand>

    fun publishUiState(
        layer: TvPlayerChromeLayer,
        panel: TvPlayerChromePanel?,
        controlsHaveFocus: Boolean,
    )
}
