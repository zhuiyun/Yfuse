package com.yfuse.feature.handoff

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.app.systemNavigationContentInset
import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.SwipeRowSystemEdge
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.inSystemBackEdge
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.remote.PhoneRemoteClient
import com.yfuse.core.remote.PhoneRemoteState
import com.yfuse.core.remote.RemotePairingTokenStore
import com.yfuse.core.security.SecureStore
import com.yfuse.feature.profile.SettingsBackButton
import com.yfuse.feature.profile.SettingsBackInset
import com.yfuse.feature.profile.SettingsHeaderTop
import com.yfuse.watch.protocol.RemoteControlKey
import com.yfuse.watch.protocol.WatchProtocol
import org.koin.core.context.GlobalContext
import kotlin.time.TimeSource

/** A swipe sends its direction once it has come this far. */
private val RemoteSwipeExtent = 48.dp

/** How often a resting finger is asked whether its held direction is due again. */
private const val REMOTE_HOLD_POLL_MS = 50L

/**
 * 遥控器: this phone as the remote and keyboard of a television of the same account, over the watch
 * relay. A swipe on the touchpad moves the television's focus one step, and held out past its edge
 * keeps moving; a tap is OK. 返回, 主页 and 播放/暂停 are buttons, and the field types into the
 * television's focused field — or its 搜索 — while the viewer types. A television that asks before
 * a phone may press anything is answered there, and until it is, this says 等待电视确认… with the
 * keys off: they used to look ready while everything they sent was dropped.
 */
@Composable
internal fun PhoneRemoteScreen(
    televisionSessionId: String,
    televisionName: String,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val haptics = LocalHaptics.current
    val client =
        remember {
            val tokens = GlobalContext.get().get<AccountAccessTokenSource>()
            PhoneRemoteClient(
                accessToken = { tokens.validAccessTokenFor(ACCOUNT_BASE_URL) },
                refreshAccessToken = { tokens.refreshAccessTokenFor(ACCOUNT_BASE_URL) },
                pairingTokens = RemotePairingTokenStore.secure(GlobalContext.get().get<SecureStore>()),
            )
        }
    DisposableEffect(client, televisionSessionId) {
        client.connect(televisionSessionId)
        onDispose { client.close() }
    }
    // Results buzz, presses do not: 连接 confirms once it has happened, a refusal rejects.
    LaunchedEffect(client) {
        var last: PhoneRemoteState = PhoneRemoteState.Idle
        client.state.collect { now ->
            if (now == PhoneRemoteState.Connected && last != PhoneRemoteState.Connected) {
                haptics.play(HapticSignal.Confirm)
            }
            if (now is PhoneRemoteState.Failed && last !is PhoneRemoteState.Failed) haptics.play(HapticSignal.Reject)
            last = now
        }
    }
    PlatformBackHandler(onBack = onBack)
    val state by client.state.collectAsState()
    val connected = state == PhoneRemoteState.Connected
    val send: (RemoteControlKey) -> Unit = { key ->
        haptics.play(if (client.sendKey(key)) HapticSignal.Tick else HapticSignal.Reject)
    }
    var text by remember { mutableStateOf("") }
    val status =
        when (val current = state) {
            PhoneRemoteState.Connected -> "已连接"
            // Joined, but the television asks first: the keys stay off until it answers.
            PhoneRemoteState.Waiting -> "等待电视确认…"
            is PhoneRemoteState.Failed -> current.message
            else -> "正在连接"
        }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(top = SettingsHeaderTop, bottom = systemNavigationContentInset()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = SettingsBackInset, end = Dimens.pageHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsBackButton(onBack)
            Column(Modifier.padding(start = 10.dp)) {
                ThemeText("遥控器", style = AppTypography.section.strong, color = palette.text)
                ThemeText(
                    "$televisionName · $status",
                    style = AppTypography.caption.regular,
                    color = if (state is PhoneRemoteState.Failed) palette.error else palette.sub2,
                    modifier = Modifier.liveStatus(assertive = state is PhoneRemoteState.Failed),
                )
            }
        }
        RemoteTouchpad(
            enabled = connected,
            waiting = state == PhoneRemoteState.Waiting,
            onKey = send,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.pageHorizontal, vertical = Dimens.space.lg),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space.md),
        ) {
            RemoteButton("返回", AppIcons.ChevronLeft, connected, { send(RemoteControlKey.Back) }, Modifier.weight(1f))
            RemoteButton("主页", AppIcons.Home, connected, { send(RemoteControlKey.Home) }, Modifier.weight(1f))
            RemoteButton("播放/暂停", AppIcons.Play, connected, { send(RemoteControlKey.PlayPause) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(Dimens.space.lg))
        YfFormField(
            value = text,
            onValueChange = { value ->
                // The relay takes a search box's worth; past that the field simply stops growing.
                if (WatchProtocol.isValidRemoteText(value)) {
                    text = value
                    client.updateText(value)
                }
            },
            label = "输入文字",
            placeholder = "打在电视的输入框或搜索里",
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        )
        (state as? PhoneRemoteState.Failed)?.takeIf { it.retryable }?.let {
            Spacer(Modifier.height(Dimens.space.md))
            OverlayButton(
                label = "重新连接",
                onClick = { client.connect(televisionSessionId) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
                tone = OverlayButtonTone.Primary,
            )
        }
    }
}

/**
 * The touchpad. One finger drives a [RemoteSwipe]; a screen reader gets it as one button whose
 * activation is OK and whose custom actions are the four directions and OK. While [waiting] it
 * says where the answer is to be given instead: on the television, not here.
 */
@Composable
private fun RemoteTouchpad(
    enabled: Boolean,
    waiting: Boolean,
    onKey: (RemoteControlKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val latestOnKey by rememberUpdatedState(onKey)
    var touching by remember { mutableStateOf(false) }
    val placement = remember { TouchpadPlacement() }
    val actions =
        remember {
            listOf(
                "向上" to RemoteControlKey.Up,
                "向下" to RemoteControlKey.Down,
                "向左" to RemoteControlKey.Left,
                "向右" to RemoteControlKey.Right,
                "确定" to RemoteControlKey.Center,
            ).map { (label, key) ->
                CustomAccessibilityAction(label) {
                    latestOnKey(key)
                    true
                }
            }
        }
    Box(
        modifier
            .onPlaced { placement.coordinates = it }
            .glass(
                shape = AppShapes.sheet,
                fill = if (touching) palette.card3 else palette.card,
                border = palette.border,
            ).semantics {
                contentDescription = "触控板，滑动移动电视上的焦点"
                role = Role.Button
                onClick(label = "确定") {
                    latestOnKey(RemoteControlKey.Center)
                    true
                }
                customActions = actions
                if (!enabled) disabled()
            }.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val extent = RemoteSwipeExtent.toPx()
                val edge = SwipeRowSystemEdge.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // The system's back edge wins, as it does for every horizontal gesture here.
                    val placed = placement.coordinates?.takeIf { it.isAttached }
                    if (placed != null) {
                        val window = placed.findRootCoordinates()
                        val startX = placed.localToRoot(down.position).x
                        if (inSystemBackEdge(startX, window.size.width.toFloat(), edge)) return@awaitEachGesture
                    }
                    val start = TimeSource.Monotonic.markNow()
                    val swipe = RemoteSwipe(slop = viewConfiguration.touchSlop, extent = extent)
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    var moved = Offset.Zero
                    var released = false
                    touching = true
                    try {
                        while (true) {
                            val event = withTimeoutOrNull(REMOTE_HOLD_POLL_MS) { awaitPointerEvent() }
                            val now = start.elapsedNow().inWholeMilliseconds
                            if (event == null) {
                                swipe.hold(now)?.let { latestOnKey(it) }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPointerInputChange(change)
                            // Read before consuming: a consumed change reports no movement at all.
                            moved += change.positionChange()
                            change.consume()
                            if (!change.pressed) {
                                released = true
                                break
                            }
                            swipe.move(moved.x, moved.y, now)?.let { latestOnKey(it) }
                        }
                    } finally {
                        touching = false
                    }
                    if (released) {
                        val velocity = tracker.calculateVelocity()
                        swipe.release(velocity.x, velocity.y)?.let { latestOnKey(it) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = Dimens.space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (waiting) {
                // OrbProgress stills under 减少动画 and breathes in place under 静息.
                OrbProgress(size = 18.dp, contentDescription = null)
                Spacer(Modifier.height(Dimens.space.sm))
                ThemeText("等待电视确认…", style = AppTypography.body.medium, color = palette.sub)
                Spacer(Modifier.height(Dimens.space.xs))
                ThemeText(
                    "在电视上选择「允许一次」或「始终允许此设备」后即可使用",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    textAlign = TextAlign.Center,
                )
            } else {
                ThemeText("滑动移动，轻点确定", style = AppTypography.body.medium, color = palette.sub)
                Spacer(Modifier.height(Dimens.space.xs))
                ThemeText("按住不放可连续移动", style = AppTypography.caption.regular, color = palette.sub2)
            }
        }
    }
}

@Composable
private fun RemoteButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val tint = if (enabled) palette.text else palette.sub2
    Column(
        modifier
            .pressable(enabled = enabled, onClick = onClick)
            .touchTarget()
            .glass(shape = AppShapes.control, fill = palette.card, border = palette.border)
            .padding(vertical = Dimens.space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ThemeIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(Dimens.space.xs))
        ThemeText(label, style = AppTypography.caption.medium, color = tint)
    }
}

/** Where the touchpad sits, for the back-edge test; read in gestures, so not state. */
private class TouchpadPlacement {
    var coordinates: LayoutCoordinates? = null
}
