package com.yfuse.feature.detail

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.cast.CastDevice
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastState
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.network.localNetworkPermissionGranted
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import com.yfuse.feature.player.PlaybackLaunchTimings
import com.yfuse.feature.player.PlaybackPreloadKey
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PreparedPlayerStore
import com.yfuse.feature.player.QuickCastTarget
import com.yfuse.feature.player.RecentCastTarget
import com.yfuse.feature.player.RecentCastTargets
import com.yfuse.feature.player.castDisplayName
import com.yfuse.feature.player.castKindOf
import com.yfuse.feature.player.quickCastTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.yfuse.core.designsystem.ThemeText as Text

/*
 * 详情页 · 投屏 — play on the television what 播放 would open here, without the phone's player.
 *
 * Nothing about the cast itself is this page's own. The title, file and resume point are what
 * [DetailIntent.Play] resolves for the play key; the queue is the player's Store for them; the load
 * is the one behind the player's 投屏 key ([castPlaybackQueue]); and the devices are the player's
 * list — what the cast manager has found, and the ones this phone cast to before.
 */

/**
 * Loads [items] onto [deviceId] from [index] at [positionMs] as the player's 投屏 does, with the same
 * queue entries, fallbacks and media profile (`loadPlaybackCastItem` on Android). True once the
 * receiver has accepted the load.
 */
internal expect suspend fun castPlaybackQueue(
    castManager: CastManager,
    items: List<PlayerMediaItem>,
    deviceId: String,
    index: Int,
    positionMs: Long,
): Boolean

/** A device picked from the detail page's 投屏 list. */
internal data class DetailCastRequest(
    val deviceId: String,
    val deviceName: String,
)

/**
 * Whether [this] plays on the phone, or changes what 播放 would open — the intents that publish a
 * play of their own or drop one still queued. A 投屏 waiting on its 播放 gives way to any of them.
 * Browsing to another season is not one: it leaves 播放's target alone, and a play waiting for it
 * still goes ahead.
 */
internal fun DetailIntent.supersedesCast(): Boolean =
    this == DetailIntent.Play ||
        this == DetailIntent.PlayFromStart ||
        this is DetailIntent.SelectEpisode ||
        this is DetailIntent.SelectSource ||
        this is DetailIntent.SelectVersion

/** How one cast from the detail page ended; [failure] is null once the receiver has the title. */
internal data class DetailCastOutcome(
    val request: DetailCastRequest,
    val failure: String?,
)

/**
 * Casts what 播放 resolved to, for [DetailComponent].
 *
 * The queue is the player's: the Store this page prepared for 播放, claimed exactly as a launching
 * player claims it so its play-session URLs serve one launch, or a fresh one built as the player
 * builds it ([queue]). The episodes after this one travel with it, so a receiver that keeps a queue
 * plays on.
 */
internal class DetailCastLauncher(
    private val scope: CoroutineScope,
    castManager: () -> CastManager?,
    recentTargets: () -> RecentCastTargets?,
    private val queue: (PlaybackPreloadKey) -> PreparedPlayerStore,
    /** A line for the page's 提示: where the title is playing now, or why it is not. */
    private val report: (String) -> Unit,
    private val load: suspend (CastManager, List<PlayerMediaItem>, String, Int, Long) -> Boolean =
        ::castPlaybackQueue,
    /** Whether a scan may start without asking — 附近的设备 already granted. */
    private val scanWithoutAsking: () -> Boolean = ::localNetworkPermissionGranted,
) {
    /** Asked for only once a page shows its 投屏 key, so a page that never does creates nothing. */
    val castManager: CastManager? by lazy(castManager)

    private val recentStore by lazy(recentTargets)

    private val mutableRecent by lazy { MutableStateFlow(recentStore?.load().orEmpty()) }

    /** The devices this phone cast or handed off to, most recent first, as the player keeps them. */
    val recent: StateFlow<List<RecentCastTarget>> get() = mutableRecent

    private val mutablePreparing = MutableStateFlow<DetailCastRequest?>(null)

    /** The cast being prepared and loaded, which the top bar's key waits on. */
    val preparing: StateFlow<DetailCastRequest?> = mutablePreparing.asStateFlow()

    private val mutableOutcomes = MutableSharedFlow<DetailCastOutcome>(extraBufferCapacity = 1)

    /** Each cast once it has ended one way or the other, for the page to answer with a haptic. */
    val outcomes: SharedFlow<DetailCastOutcome> = mutableOutcomes.asSharedFlow()

    private var job: Job? = null

    /** Reads the remembered devices again: the player may have added one since. */
    fun refreshRecent() {
        mutableRecent.value = recentStore?.load().orEmpty()
    }

    fun launch(
        request: DetailCastRequest,
        key: PlaybackPreloadKey,
    ) {
        job?.cancel()
        mutablePreparing.value = request
        job =
            scope.launch {
                val manager = castManager
                val failure = if (manager == null) "此设备暂不支持投屏" else cast(manager, request, key)
                // Released as a player's first frame would release it, so what this page held back
                // behind the tap — the season list, the source comparison — does not wait out the
                // 30-second deadline for a player that is never going to open.
                PlaybackLaunchTimings
                    .find(key.serverId, key.itemId)
                    ?.stage(if (failure == null) "cast_loaded" else "cast_failed", output = true)
                val name = castDisplayName(request.deviceName)
                if (failure == null) {
                    recentStore
                        ?.record(RecentCastTarget(castKindOf(request.deviceId), request.deviceId, name))
                        ?.let { mutableRecent.value = it }
                }
                report(failure ?: "已在「$name」开始播放")
                mutablePreparing.value = null
                mutableOutcomes.tryEmit(DetailCastOutcome(request, failure))
            }
    }

    /** 停止投屏: the television stops; nothing plays on here, so there is nothing to hand back to. */
    fun stop() {
        val manager = castManager ?: return
        scope.launch {
            if (!manager.stop()) report("投屏设备未确认停止，请重试")
        }
    }

    /** Null once the receiver has the title; otherwise why it has not. */
    private suspend fun cast(
        manager: CastManager,
        request: DetailCastRequest,
        key: PlaybackPreloadKey,
    ): String? {
        val store = queue(key)
        try {
            // The Store's own watchdog ends a load that hangs, in the player's words.
            val ready = store.states.first { !it.loading }
            ready.error?.let { return it }
            // Bounded: what the television plays first is known already, and the later episodes come
            // along only if they are there in time.
            val queued = withTimeoutOrNull(CAST_QUEUE_WAIT_MS) { store.states.first { !it.enrichmentPending } }
            val playback = queued ?: store.state
            if (!awaitDevice(manager, request.deviceId)) return "没有找到「${castDisplayName(request.deviceName)}」"
            val loaded = load(manager, playback.items, request.deviceId, playback.startIndex, playback.startPositionMs)
            return if (loaded) null else manager.state.value.error ?: "投屏失败"
        } finally {
            store.dispose()
        }
    }

    /**
     * A device remembered from an earlier film and not found by this session's scan yet is looked for
     * first, as the player's list does — but never behind a permission prompt, which belongs to the
     * list's own scan and not to a tap on one of its rows.
     */
    private suspend fun awaitDevice(
        manager: CastManager,
        deviceId: String,
    ): Boolean {
        val known = manager.state.value
        if (known.devices.any { it.id == deviceId }) return true
        if (!known.discovering && scanWithoutAsking()) {
            scope.launch { manager.discover() }
        }
        return withTimeoutOrNull(CAST_FIND_TIMEOUT_MS) {
            manager.state.first { state -> state.devices.any { it.id == deviceId } }
        } != null
    }
}

/** What of the cast manager's state the page shows — without the receiver's ticking position. */
internal data class DetailCastSummary(
    val devices: List<CastDevice> = emptyList(),
    val discovering: Boolean = false,
    val activeDeviceId: String? = null,
    val activeDeviceName: String? = null,
    /** Connecting or live, as the app's status capsule counts it. */
    val casting: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
)

private fun CastState.summary(): DetailCastSummary =
    DetailCastSummary(
        devices = devices,
        discovering = discovering,
        activeDeviceId = activeDeviceId,
        activeDeviceName = activeDevice?.name?.let(::castDisplayName),
        casting = hasActiveSession || status == CastPlaybackStatus.Connecting,
        connecting = status == CastPlaybackStatus.Connecting,
        error = error,
    )

/**
 * The page's side of 投屏: what the top bar's key says, and what its device list offers.
 *
 * The key stands while there is something to cast to — a device the manager has found, or one this
 * phone has cast to before — and while a cast is live; with none of those it is not there at all.
 * State is read where it is drawn, so a scan or a receiver's report repaints the key and the list,
 * never the page.
 */
@Stable
internal class DetailCast(
    private val launcher: DetailCastLauncher,
    private val summary: State<DetailCastSummary>,
    private val recent: State<List<RecentCastTarget>>,
    private val preparing: State<DetailCastRequest?>,
    /** Scans for devices, asking for 附近的设备 first where that is still to be granted. */
    val scan: () -> Unit,
) {
    /** Whether the device list is open. */
    var listOpen by mutableStateOf(false)
        private set

    /**
     * The player's list for 按住拖送, without 接力 — which hands over a playback this page does not
     * have: remembered devices, most recent first, then what the scan found.
     */
    val targets: List<QuickCastTarget>
        get() =
            quickCastTargets(
                recent = recent.value,
                castDevices = summary.value.devices,
                receivers = emptyList(),
                activeCastId = summary.value.activeDeviceId,
                handoffAllowed = false,
                limit = Int.MAX_VALUE,
            )

    val casting: Boolean get() = summary.value.casting

    val available: Boolean get() = casting || targets.isNotEmpty()

    /** A cast from this page is being prepared and loaded. */
    val busy: Boolean get() = preparing.value != null

    val discovering: Boolean get() = summary.value.discovering

    val devicesFound: Boolean get() = summary.value.devices.isNotEmpty()

    val error: String? get() = summary.value.error

    /** Read after 投屏: where it is playing, or that it is on its way. */
    val stateLabel: String?
        get() {
            val state = summary.value
            val device = state.activeDeviceName
            return when {
                busy -> "正在准备投屏"
                state.connecting -> device?.let { "正在连接 $it" } ?: "正在连接"
                state.casting -> device?.let { "正在投屏到 $it" } ?: "正在投屏"
                else -> null
            }
        }

    fun open() {
        launcher.refreshRecent()
        listOpen = true
    }

    fun close() {
        listOpen = false
    }

    fun stop() = launcher.stop()
}

/** [DetailCast] for [component]; null where the platform has no cast manager. */
@Composable
internal fun rememberDetailCast(component: DetailComponent): DetailCast? {
    val launcher = component.castLauncher
    val manager = launcher.castManager ?: return null
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val summaries = remember(manager) { manager.state.map { it.summary() }.distinctUntilChanged() }
    val summary = summaries.collectAsState(manager.state.value.summary())
    val recent = launcher.recent.collectAsState()
    val preparing = launcher.preparing.collectAsState()
    // A denial reaches the manager too, so the list says why it is empty instead of idling.
    val scan by rememberUpdatedState(
        rememberLocalNetworkPermissionRequest(
            onGranted = { scope.launch { manager.discover() } },
            onDenied = { scope.launch { manager.discover() } },
        ),
    )
    LaunchedEffect(launcher) {
        launcher.outcomes.collect { outcome ->
            haptics.play(if (outcome.failure == null) HapticSignal.Confirm else HapticSignal.Reject)
        }
    }
    return remember(launcher, summary, recent, preparing) {
        DetailCast(launcher, summary, recent, preparing, scan = { scan() })
    }
}

/**
 * 投屏's device list on the detail page — the player's 投屏 list, for what 播放 would open here.
 *
 * Opened with nothing found, it scans at once, as the player's panel does. A device picked plays the
 * title there straight away, and the list gives way to the page; 停止投屏 ends a cast already going.
 */
@Composable
internal fun DetailCastSheet(
    cast: DetailCast,
    /** Where 播放 resumes, in Emby ticks; said with [detailLine] as the list's subtitle. */
    resumeTicks: Long,
    /** The play key's `第 2 季 · 第 3 集 · 45分钟`. */
    detailLine: String?,
    onPick: (DetailCastRequest) -> Unit,
) {
    val palette = LocalPalette.current
    // Until the manager reports the scan running, an empty list still means "searching" rather
    // than "nothing found" — and a scan that never starts (a prompt left unanswered) must not
    // leave the list saying it is searching.
    var scanStarting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!cast.devicesFound && !cast.discovering) {
            scanStarting = true
            cast.scan()
            delay(CAST_SCAN_START_GRACE_MS)
            scanStarting = false
        }
    }
    val searching = cast.discovering || scanStarting
    GlassDialog(onDismiss = cast::close, liquidButtons = false) {
        OverlayHeader(title = "投屏", subtitle = detailCastSubtitle(resumeTicks, detailLine), onClose = cast::close)
        cast.targets.forEach { target ->
            key(target.kind, target.id) {
                OverlayOptionRow(
                    label = target.name,
                    description = castTargetCaption(target, searching),
                    // The one already playing is marked; picking it plays this title there instead.
                    selected = target.active,
                    role = Role.Button,
                    onClick =
                        overlayAction {
                            cast.close()
                            onPick(DetailCastRequest(target.id, target.name))
                        },
                )
            }
        }
        val emptyState =
            when {
                searching -> "正在搜索投屏设备…"
                !cast.devicesFound && cast.error == null -> "未发现设备，请确认电视与手机连接同一局域网"
                else -> null
            }
        emptyState?.let { line ->
            Text(
                line,
                style = AppTypography.caption.medium,
                color = palette.sub2,
                modifier = Modifier.padding(vertical = Dimens.space.sm).liveStatus(),
            )
        }
        cast.error?.let { error ->
            Text(
                error,
                style = AppTypography.caption.medium,
                color = palette.error,
                modifier = Modifier.padding(vertical = Dimens.space.sm).liveStatus(),
            )
        }
        if (cast.casting) OverlayActionRow("停止投屏", onClick = cast::stop)
        OverlayActionRow("重新扫描", onClick = cast.scan)
    }
}

/** What the device list says goes to the television: the play key's words, its episode and its clock. */
internal fun detailCastSubtitle(
    resumeTicks: Long,
    /** The play key's `第 2 季 · 第 3 集 · 45分钟`. */
    detailLine: String?,
): String =
    listOfNotNull(
        if (resumeTicks > 0L) "继续播放" else "播放",
        detailLine,
        formatResumePosition(resumeTicks),
    ).joinToString(" · ")

/** Under a device's name: what kind of receiver it is, and whether it is here now. */
internal fun castTargetCaption(
    target: QuickCastTarget,
    searching: Boolean,
): String =
    when {
        target.active -> "正在投屏 · ${target.kind.caption}"
        target.found -> target.kind.caption
        searching -> "上次用过 · 正在查找"
        else -> "上次用过 · 暂未找到"
    }

/** The top bar's 投屏 key grows in beside 更多 when a device turns up; under 减少动画 and 静息, it fades. */
internal fun castKeyEnter(still: Boolean): EnterTransition =
    if (still) {
        fadeIn(Motion.tween(Motion.REDUCED_FADE))
    } else {
        fadeIn(Motion.tween(Motion.QUICK)) + expandHorizontally(Motion.tween(Motion.QUICK), Alignment.End)
    }

internal fun castKeyExit(still: Boolean): ExitTransition =
    if (still) {
        fadeOut(Motion.tween(Motion.REDUCED_FADE))
    } else {
        fadeOut(Motion.tween(Motion.QUICK)) + shrinkHorizontally(Motion.tween(Motion.QUICK), Alignment.End)
    }

/** The player's own waits: a remembered device is looked for this long before the pick gives up. */
private const val CAST_FIND_TIMEOUT_MS = 8_000L

/** The later episodes travel with the one being cast if they are known by then. */
private const val CAST_QUEUE_WAIT_MS = 4_000L

/** Long enough for the manager to report a scan it has started, as on the player's 投屏 page. */
private const val CAST_SCAN_START_GRACE_MS = 2_000L
