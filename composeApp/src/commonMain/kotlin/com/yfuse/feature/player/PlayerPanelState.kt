package com.yfuse.feature.player

import com.yfuse.core.data.SkipMode
import com.yfuse.core.sync.WatchChatMessage
import com.yfuse.core.sync.WatchControlMode
import com.yfuse.core.sync.WatchParticipant
import com.yfuse.core.sync.WatchReactionBurst
import com.yfuse.core.sync.WatchReaction

/**
 * The 一起看 room, as the player chrome sees it.
 *
 * Fifteen parameters before this existed, all prefixed `watch`, all threaded through
 * [PlayerControls] and [SettingsPanel] individually. They describe one thing — the state of
 * one room — and a caller that has to pass fourteen of them and forget the fifteenth has
 * been given fifteen chances to make a mistake instead of one.
 *
 * Same reasoning as [DanmakuPanelState], and the same shape, so there is one way to read
 * the player's signature rather than three.
 */
data class WatchRoomState(
    val endpoint: String = "",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    /**
     * True while a previously-established connection is retrying. Distinct from [connected]:
     * the room and its controls stay visible throughout, this only adds a 重连中 indicator.
     */
    val reconnecting: Boolean = false,
    val roomCode: String? = null,
    val isHost: Boolean = false,
    val canControl: Boolean = false,
    val controlMode: WatchControlMode = WatchControlMode.HostOnly,
    val participantCount: Int = 0,
    val participants: List<WatchParticipant> = emptyList(),
    val chatMessages: List<WatchChatMessage> = emptyList(),
    val chatError: String? = null,
    /** Reactions still floating up the screen — see [WatchReactionOverlay]. */
    val reactions: List<WatchReactionBurst> = emptyList(),
    val chatPreviewEnabled: Boolean = true,
    val chatDanmakuEnabled: Boolean = true,
    val error: String? = null,
    /** This device has asked the host for control and hasn't been answered yet. */
    val controlRequested: Boolean = false,
    /** Host side: who is asking for control right now, by name. Null when nobody is. */
    val controlRequesterName: String? = null,
) {
    /**
     * True when the timeline is somebody else's to move.
     *
     * The one derived fact the whole chrome keys off — play/pause, seek, episode and speed
     * are all read-only for a connected non-host — so it is stated once here rather than
     * re-derived at each of the four places that need it.
     */
    val locked: Boolean get() = connected && !canControl
}

data class WatchRoomActions(
    val onCreate: (String) -> Unit = {},
    val onJoin: (String, String) -> Unit = { _, _ -> },
    val onLeave: () -> Unit = {},
    val onRequestControl: () -> Unit = {},
    val onGrantControl: () -> Unit = {},
    val onDenyControl: () -> Unit = {},
    val onSendChat: (String) -> Boolean = { false },
    val onRetryChat: (String) -> Unit = {},
    val onClearChatError: () -> Unit = {},
    val onToggleChatDanmaku: () -> Unit = {},
    val onReact: (WatchReaction) -> Unit = {},
    /** The bubble has floated off; drop it from the room state. */
    val onReactionFinished: (Long) -> Unit = {},
    val onSetControlMode: (WatchControlMode) -> Unit = {},
    val onSetModerator: (String, Boolean) -> Unit = { _, _ -> },
    val onKickParticipant: (String) -> Unit = {},
)

/**
 * 片头片尾 — what is set for this show, and what the player does when it gets there.
 *
 * The intro is stored as absolute positions and the credits as a distance from the end,
 * because that is what stays constant for each; see `SkipTimes.creditsLeadSeconds`.
 */
data class SkipSegmentState(
    /** `跳过片头` — non-null only while playback is actually inside a segment. */
    val segmentLabel: String? = null,
    /** Seconds left before an automatic skip fires, or null when none is armed. */
    val countdownSeconds: Int? = null,
    /** Non-null when this entry belongs to a series, which is what times are kept per. */
    val seriesName: String? = null,
    val introStartSeconds: Long = 0L,
    val introEndSeconds: Long = 0L,
    /** 片尾 starts this many seconds before the end. */
    val creditsLeadSeconds: Long = 0L,
    val mode: SkipMode = SkipMode.Button,
) {
    /** True once any boundary is set, including a half-entered intro. */
    val anySet: Boolean
        get() = introStartSeconds > 0L || introEndSeconds > 0L || creditsLeadSeconds > 0L
}

data class SkipSegmentActions(
    val onSkip: () -> Unit = {},
    /** Drops back to the manual pill rather than clearing the offer outright. */
    val onCancelAuto: () -> Unit = {},
    val onSetTimes: (Long, Long, Long) -> Unit = { _, _, _ -> },
    val onSelectMode: (SkipMode) -> Unit = {},
)
