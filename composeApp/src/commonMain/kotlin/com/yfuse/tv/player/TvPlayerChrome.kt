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
 * What OK over hidden chrome acts on while it is on screen, since a remote cannot reach it any
 * other way without first raising the controls — and spending the moment it offers.
 */
enum class TvPlayerPrompt {
    /** 跳过片头 / 跳过片尾, or an automatic skip's countdown: OK skips, or calls the skip off. */
    Skip,

    /**
     * The 下一集 card at the end of an episode, or the credits takeover's card beside the shrunken
     * picture: OK plays the next episode, Back stays with the credits.
     */
    NextUp,
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
    /**
     * The seek key is being held — it has repeated — rather than tapped. A tap jumps ten seconds
     * and lets go within a frame or two; only a hold travels far enough to need a picture.
     */
    val seekHeld: Boolean = false,
    val interactionRevision: Long = 0L,
    /**
     * True while a control surface is composed, publishing its layer and collecting commands.
     *
     * The preparation screen before playback has none, and neither do the player's first frames.
     * Until the controls publish, [layer] would only be a guess that nothing answers, so D-pad, OK
     * and Back must stay ordinary focus input rather than steer chrome that is not there.
     */
    val attached: Boolean = false,
    /** The prompt OK over hidden chrome acts on instead of pausing; null when there is none. */
    val prompt: TvPlayerPrompt? = null,
) {
    /** A skip prompt is on screen: the 跳过片头 / 片尾 pill, or an automatic skip's countdown. */
    val skipPrompt: Boolean get() = prompt == TvPlayerPrompt.Skip

    val visible: Boolean get() = layer != TvPlayerChromeLayer.Hidden
    val hasDismissibleLayer: Boolean get() = visible

    /**
     * 全程缩略图 for the remote: where a held fast-forward or rewind has got to, for the trickplay
     * card over the picture; null when no seek is being held.
     */
    val holdPreviewMs: Long? get() = seekTargetMs?.takeIf { seeking && seekHeld }
}

enum class TvPlayerChromeCommandType {
    ShowControls,
    HideControls,
    CloseTop,

    /** The remote's CC key: the subtitle and audio panel. */
    OpenTracks,

    /** The remote's INFO key: the media information panel. */
    OpenInfo,

    /** OK over hidden chrome with a skip prompt up: skip the segment, or call off the automatic skip. */
    ActivateSkipPrompt,

    /** OK over hidden chrome with the 下一集 card up: play the next episode now. */
    ActivateNextUp,

    /** Back over hidden chrome with the 下一集 card up: keep watching the credits. */
    DismissNextUp,
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

    /** Which prompt, if any, is on screen for OK to act on; see [TvPlayerChromeState.prompt]. */
    fun publishPrompt(prompt: TvPlayerPrompt?)

    /** The control surface left composition; remote keys fall back to ordinary dispatch until it returns. */
    fun detach()
}
