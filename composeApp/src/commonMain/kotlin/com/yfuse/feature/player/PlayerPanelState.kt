package com.yfuse.feature.player

import com.yfuse.core.data.SkipMode
import com.yfuse.core.sync.WatchChatMessage
import com.yfuse.core.sync.WatchControlMode
import com.yfuse.core.sync.WatchParticipant
import com.yfuse.core.sync.WatchReaction
import com.yfuse.core.sync.WatchReactionBurst

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
    /** Account-backed Watch is hidden until an authenticated Yfuse session is available. */
    val available: Boolean = false,
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
    /**
     * Sends a floating reaction. Nothing in this app's chrome calls it any more — the sticker
     * tray sends its picks as chat messages instead, which is what puts them in front of both
     * ends of the room rather than behind whichever panel did the sending. It stays because
     * the *receiving* half is still live: a room is a relay, and a client on an older build
     * still sends `type=reaction`, which [WatchReactionOverlay] still draws.
     */
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

data class SubtitleControlState(
    val offsetMs: Long = 0L,
    val scale: Float = 1f,
    val brightness: Float = 1f,
    val position: Float = DEFAULT_SUBTITLE_POSITION,
    val stylePreset: SubtitleStylePreset = SubtitleStylePreset.Standard,
    val appearance: SubtitleAppearance = SubtitleAppearance(),
    val secondaryTrackId: String? = null,
    val secondarySupported: Boolean = false,
    val secondaryUnavailableReason: String? = null,
    val offsetAvailable: Boolean = true,
    val scaleAvailable: Boolean = true,
    val brightnessAvailable: Boolean = true,
    val positionAvailable: Boolean = true,
    val appearanceAvailable: Boolean = true,
    val unavailableReason: String? = null,
)

data class SubtitleControlActions(
    val onOffset: (Long) -> Unit = {},
    val onScale: (Float) -> Unit = {},
    val onBrightness: (Float) -> Unit = {},
    val onPosition: (Float) -> Unit = {},
    val onStylePreset: (SubtitleStylePreset) -> Unit = {},
    val onTextColor: (Long) -> Unit = {},
    val onBackgroundColor: (Long) -> Unit = {},
    val onOutlineColor: (Long) -> Unit = {},
    val onOutlineWidth: (Float) -> Unit = {},
    val onSecondaryTrack: (String) -> Unit = {},
)

enum class SubtitleStylePreset(
    val label: String,
    val scale: Float,
    val brightness: Float,
    val position: Float,
    val appearance: SubtitleAppearance,
) {
    Standard("标准", 1f, 1f, DEFAULT_SUBTITLE_POSITION, SubtitleAppearance()),
    Cinema(
        "影院",
        1.05f,
        0.85f,
        0.88f,
        SubtitleAppearance(textColorArgb = 0xFFFFF2CCL, outlineWidth = 2.5f),
    ),
    Compact(
        "紧凑",
        0.85f,
        0.80f,
        0.94f,
        SubtitleAppearance(outlineWidth = 1.5f),
    ),
    Accessible(
        "大字幕",
        1.30f,
        1f,
        0.84f,
        SubtitleAppearance(backgroundColorArgb = 0x99000000L, outlineWidth = 3f),
    ),
    Custom("自定义", 1f, 1f, DEFAULT_SUBTITLE_POSITION, SubtitleAppearance()),
}

data class AudioControlState(
    val delayMs: Long = 0L,
    val measuredAvOffsetMs: Long? = null,
    val enhancement: AudioEnhancementMode = AudioEnhancementMode.Off,
    val available: Boolean = true,
    val enhancementAvailable: Boolean = true,
    val unavailableReason: String? = null,
)

data class AudioControlActions(
    val onDelay: (Long) -> Unit = {},
    val onAutoSync: () -> Unit = {},
    val onEnhancement: (AudioEnhancementMode) -> Unit = {},
)

internal fun calibratedAudioDelayMs(
    currentDelayMs: Long,
    measuredVideoOffsetMs: Long,
): Long = (currentDelayMs - measuredVideoOffsetMs).coerceIn(-2_000L, 2_000L)

const val DEFAULT_SUBTITLE_POSITION = 0.92f

enum class SleepTimerOption(
    val label: String,
    val durationMs: Long?,
) {
    Off("关闭", null),
    Minutes15("15 分钟", 15 * 60_000L),
    Minutes30("30 分钟", 30 * 60_000L),
    Minutes45("45 分钟", 45 * 60_000L),
    Minutes60("60 分钟", 60 * 60_000L),
    EndOfEpisode("本集结束", null),
}

data class SleepTimerState(
    val selected: SleepTimerOption = SleepTimerOption.Off,
)

data class SleepTimerActions(
    val onSelect: (SleepTimerOption) -> Unit = {},
)

internal fun shouldCompleteLocalEndOfEpisodeTimer(
    armedIndex: Int?,
    currentIndex: Int,
    ended: Boolean,
    playing: Boolean,
    armedItemReachedEnd: Boolean,
): Boolean =
    armedIndex != null &&
        (
            (currentIndex == armedIndex && ended) ||
                (currentIndex != armedIndex && !playing && armedItemReachedEnd)
        )

internal fun shouldCompleteCastEndOfEpisodeTimer(
    armedIndex: Int?,
    armedSessionRevision: Long?,
    currentIndex: Int,
    currentSessionRevision: Long,
    castEnded: Boolean,
): Boolean =
    castEnded &&
        armedIndex == currentIndex &&
        armedSessionRevision == currentSessionRevision

data class RemoteSubtitleOption(
    val id: String,
    val label: String,
    val detail: String,
)

data class RemoteSubtitlePanelState(
    val loading: Boolean = false,
    val results: List<RemoteSubtitleOption> = emptyList(),
    val downloadingId: String? = null,
    val message: String? = null,
)

data class RemoteSubtitleActions(
    val onSearch: () -> Unit = {},
    val onDownload: (String) -> Unit = {},
)
