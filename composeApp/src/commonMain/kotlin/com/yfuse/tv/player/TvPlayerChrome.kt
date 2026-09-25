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
    /**
     * True while a control surface is composed, publishing its layer and collecting commands.
     *
     * The preparation screen before playback has none, and neither do the player's first frames.
     * Until the controls publish, [layer] would only be a guess that nothing answers, so D-pad, OK
     * and Back must stay ordinary focus input rather than steer chrome that is not there.
     */
    val attached: Boolean = false,
) {
    val visible: Boolean get() = layer != TvPlayerChromeLayer.Hidden
    val hasDismissibleLayer: Boolean get() = visible
}

enum class TvPlayerChromeCommandType {
    ShowControls,
    HideControls,
    CloseTop,

    /** The remote's CC key: the subtitle and audio panel. */
    OpenTracks,

    /** The remote's INFO key: the media information panel. */
    OpenInfo,
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

    /** The control surface left composition; remote keys fall back to ordinary dispatch until it returns. */
    fun detach()
}
