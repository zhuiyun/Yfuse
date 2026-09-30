package com.yfuse.feature.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import com.yfuse.core.data.PlayerGestureSettings
import com.yfuse.core.designsystem.AmbientLight
import com.yfuse.core.model.PlaybackChapter
import com.yfuse.tv.player.TvPlayerChromeBridge

/*
 * What PlayerControls is handed, one area at a time: a snapshot of the area's state and the
 * callbacks that act on it.
 *
 * Ninety-one parameters before these existed, threaded through one signature in no order the
 * caller could lean on. Grouped the way [WatchRoomState] and [DanmakuPanelState] already were, the
 * call site reads by area — the transport, the picture, tracks, the source, 更多's pages, 投屏,
 * 弹幕, 一起看 and what the player root lends the chrome — and a callback cannot drift away from the
 * state it answers. Every field keeps the meaning and, where it had one, the default it had as a
 * parameter. The readers (volume, brightness, 弹幕热度, the cast position) stay readers: as values,
 * each of their changes recomposed every control on the screen.
 */

/** The timeline and the queue: the transport keys, the rail, 片头片尾 and the end-of-item cards. */
@Immutable
internal data class PlayerTransportState(
    /** The queue, as the strip and the title bar both read it. */
    val episodes: List<EpisodeCard>,
    /** Where playback resumed from, when it did; shows a brief 从头开始 offer. */
    val resumedFromMs: Long? = null,
    /** 自动播放下一集, as the engine was built with it: off, nothing counts down to the next item. */
    val autoNext: Boolean = true,
    val trickplay: TrickplayStoryboard? = null,
    /** The file's named chapters: the progress bar is divided at them and the preview names them. */
    val chapters: List<PlaybackChapter> = emptyList(),
    val skip: SkipSegmentState = SkipSegmentState(),
)

@Immutable
internal data class PlayerTransportActions(
    val onPlayPause: () -> Unit,
    val onRetry: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onSelectItem: (Int) -> Unit,
    val onPreviousItem: () -> Boolean,
    val onNextItem: () -> Boolean,
    /** 取消 on the next-up card: the engine must not advance on its own either. */
    val onDismissNextUp: () -> Unit = {},
    /** 片尾接管: true while the credits have the picture in its corner; the caller shrinks the surface. */
    val onCreditsTakeover: (Boolean) -> Unit = {},
    val onRefreshEpisodes: () -> Unit,
    val onSpeed: (Float) -> Unit,
    /**
     * 长按中间: the speed to play at while the middle third is held, or null once it is let go.
     * Temporary by contract — the caller must not remember it as the series' speed.
     */
    val onSpeedBoost: (Float?) -> Unit = {},
    val skip: SkipSegmentActions = SkipSegmentActions(),
)

/** The picture and what the viewer does to it: fill, sound, light, 氛围光 and the gestures on it. */
@Immutable
internal data class PlayerPictureState(
    val scaleMode: VideoScaleMode,
    /** 手势 from 播放设置: the double-tap step, whether the middle holds a speed, which side is which. */
    val gestures: PlayerGestureSettings = PlayerGestureSettings(),
    /** The top of the system's gesture area: the picture's own drags and taps start below it. */
    val systemGestureTopPx: Float = 0f,
    /*
     * System volume, 0f..1f, and its setter — read by the right-edge drag gesture and by the
     * slider the volume rocker raises. There is no on-screen volume control any more. A reader
     * rather than a value: a drag or the rocker changes it many times a second, and as a value
     * each of those recomposed every control on this screen.
     */
    val volume: () -> Float = { 0f },
    /*
     * Increments on each volume key press. Any change raises the vertical slider; the value
     * itself is meaningless, which is what lets a press at the volume ceiling still show it.
     */
    val volumeKeyPresses: Long = 0L,
    // Current window brightness, 0f..1f, as a reader for the same reason. Vertical drags on the left half adjust it.
    val brightness: () -> Float = { 0.5f },
    /** 氛围光 for the scrims and seek accent; null while the light is off. */
    val ambientLight: State<AmbientLight>? = null,
    val ambientLightEnabled: Boolean = true,
)

@Immutable
internal data class PlayerPictureActions(
    val onToggleFill: (stretch: Boolean) -> Unit,
    /** 捏合填充 and the F key: 裁剪填满 (true) or 适应 (false), remembered for the series like the button. */
    val onSetFill: (Boolean) -> Unit = {},
    val onVolume: (Float) -> Unit = {},
    val onBrightness: (Float) -> Unit = {},
    val onToggleAmbientLight: () -> Unit = {},
    val onAmbientChromeVisibleChange: (Boolean) -> Unit = {},
)

/** 音轨 and 字幕: the engine's tracks are in the playback state; these are the viewer's adjustments. */
@Immutable
internal data class PlayerTrackState(
    val audio: AudioControlState = AudioControlState(),
    val subtitles: SubtitleControlState = SubtitleControlState(),
    val remoteSubtitles: RemoteSubtitlePanelState = RemoteSubtitlePanelState(),
)

@Immutable
internal data class PlayerTrackActions(
    val onSelectAudio: (String) -> Unit,
    val audio: AudioControlActions = AudioControlActions(),
    val onSelectSubtitle: (String) -> Unit,
    /**
     * 没听清: show [SubtitlePeek.trackId] on the engine for the replay. Temporary by contract — the
     * caller keeps it out of series memory, preferences and its own restore state.
     */
    val onPeekSubtitle: (SubtitlePeek) -> Unit = {},
    /** 没听清 is over: put the given track back ([EngineTrack.OFF] included), or with null touch nothing. */
    val onEndSubtitlePeek: (String?) -> Unit = {},
    val subtitles: SubtitleControlActions = SubtitleControlActions(),
    val remoteSubtitles: RemoteSubtitleActions = RemoteSubtitleActions(),
)

/**
 * What plays and through what: 线路 (the same title on another server), 版本 (another file of it),
 * the engine strategy, 转码播放 and disc navigation, and the facts the readout and badges name.
 */
@Immutable
internal data class PlayerSourceState(
    // The server this file is on. Null when there is only ever one server to be on.
    val sourceLabel: String? = null,
    // Resolved copies of the current item on other servers.
    val sourceOptions: List<Pair<String, String>> = emptyList(),
    val selectedSourceId: String? = null,
    // `MKV` — the container, which the engine cannot report but the library knows.
    val containerLabel: String? = null,
    val dolbyVision: Boolean = false,
    val dolbyAtmos: Boolean = false,
    // Files the server holds for this entry; a picker appears once there are two.
    val versions: List<Pair<String, String>> = emptyList(),
    val selectedVersionId: String? = null,
    // Engine picker rows: label to selected.
    val engineOptions: List<Pair<String, Boolean>> = emptyList(),
    // Null when the active engine has no transcode fallback.
    val transcodeLabel: String? = null,
    val transcodeActive: Boolean = false,
)

@Immutable
internal data class PlayerSourceActions(
    val onSelectSource: (String) -> Unit = {},
    val onSelectVersion: (String) -> Unit = {},
    val onSelectEngine: (Int) -> Unit = {},
    val onTranscode: () -> Unit = {},
    val onResetAdaptiveLearning: () -> Unit = {},
    val onNextDiscTitle: () -> Unit = {},
    val onNextDiscChapter: () -> Unit = {},
    val onShowDiscMenu: () -> Unit = {},
    val onExternalPlayer: (() -> Unit)? = null,
)

/** 更多's own pages: 书签 and 睡眠定时. */
@Immutable
internal data class PlayerPanelState(
    val bookmarks: PlaybackBookmarkPanelState = PlaybackBookmarkPanelState(),
    val sleepTimer: SleepTimerState = SleepTimerState(),
)

@Immutable
internal data class PlayerPanelActions(
    val bookmarks: PlaybackBookmarkActions = PlaybackBookmarkActions(),
    val sleepTimer: SleepTimerActions = SleepTimerActions(),
)

/** 投屏: the receivers found, the one in use, and what it reports. */
@Immutable
internal data class PlayerCastState(
    val devices: List<Pair<String, String>> = emptyList(),
    /** The receiver this is casting to; null while nothing is. */
    val deviceId: String? = null,
    val discovering: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    /** A session is connecting or live on a receiver; [status] then names it and its state. */
    val active: Boolean = false,
    val position: String? = null,
    /** The receiver's own clock, resolved inside 投屏 rather than by whoever opened the panel. */
    val positionSource: (() -> String?)? = null,
    val capabilities: String? = null,
)

@Immutable
internal data class PlayerCastActions(
    val onDiscover: () -> Unit = {},
    val onCastTo: (String) -> Unit = {},
    val onStop: () -> Unit = {},
)

/** 弹幕: the panel's state, and 弹幕热度 for the rail. Its callbacks are [DanmakuPanelActions]. */
@Immutable
internal data class PlayerDanmakuState(
    val panel: DanmakuPanelState = DanmakuPanelState(),
    /** 弹幕热度 of the matched comments, read while the rail draws; null when nothing is matched. */
    val heat: () -> DanmakuHeat? = { null },
)

/**
 * What the player root lends the chrome: the ways out of the player, the television remote's
 * bridge, whether a keyboard is attached, what it adds to the chrome, and its wake-ups.
 */
@Immutable
internal data class PlayerChromeHost(
    val onBack: () -> Unit,
    val onEnterPictureInPicture: (() -> Unit)?,
    val remoteChrome: TvPlayerChromeBridge? = null,
    /**
     * A hardware keyboard is attached: 键盘快捷键 answer. Ignored with [remoteChrome], because TV keeps
     * its remote controller, which sees every key before the window does.
     */
    val hardwareKeyboard: Boolean = false,
    /** 点弹幕, 旋转锁 and the press-and-slide keys, supplied by the player root; see [PlayerChromeExtras]. */
    val extras: PlayerChromeExtras = PlayerChromeExtras(),
    /** Bumped by the owner to bring the controls up, as a tap on the picture would. */
    val wakeRequests: Int = 0,
)
