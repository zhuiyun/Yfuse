package com.yfuse.feature.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.russhwolf.settings.Settings
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastState
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.handoff.HandoffUiState
import com.yfuse.core.network.localNetworkPermissionGranted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext

/** How long a remembered cast device that the scan has not found yet is waited for. */
private const val CAST_FIND_TIMEOUT_MS = 8_000L

/** How long [HandoffController.send] gets to start a transfer before its silence counts as a refusal. */
private const val HANDOFF_START_TIMEOUT_MS = 2_000L

/** A scan this recent is fresh enough that opening the list again does not start another. */
private const val QUICK_SCAN_INTERVAL_MS = 60_000L

/**
 * 按住拖送 for this player: what the cast key's list offers, and what letting go on a row does.
 *
 * @property pick handed to the top bar through [PlayerChromeExtras].
 * @property noteCast remembers a cast made from the 投屏 panel, so the list's order is the order
 *   devices were really used in, whichever way they were chosen.
 */
internal class PlayerQuickCast(
    val pick: CastQuickPick,
    val noteCast: (String) -> Unit,
)

/**
 * Casting goes through [castTo] — the load the 投屏 panel already uses, which pauses this phone
 * only once the receiver has the film. Handing off to another of the account's devices goes
 * through [HandoffController.send], whose rule stands: this phone pauses only after the receiving
 * device is ready. Either way a failure leaves playback here as it was, says why, and plays
 * [HapticSignal.Reject]; success plays [HapticSignal.Confirm].
 */
@Composable
internal fun rememberPlayerQuickCast(
    castManager: CastManager,
    castState: CastState,
    handoffAllowed: Boolean,
    requestDiscovery: () -> Unit,
    castTo: suspend (String) -> Boolean,
): PlayerQuickCast {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val latestCastTo by rememberUpdatedState(castTo)
    val latestRequest by rememberUpdatedState(requestDiscovery)
    val store = remember { runCatching { RecentCastTargets(GlobalContext.get().get<Settings>()) }.getOrNull() }
    var recent by remember { mutableStateOf(store?.load().orEmpty()) }
    var lastScanAt by remember { mutableLongStateOf(0L) }
    val handoff = remember { runCatching { GlobalContext.get().getOrNull<HandoffController>() }.getOrNull() }
    val handoffFlow = remember(handoff) { handoff?.state ?: MutableStateFlow(HandoffUiState()) }
    val handoffState by handoffFlow.collectAsState()
    val receivers =
        if (handoffState.online) {
            handoffState.devices.map { HandoffReceiver(it.sessionId, it.name) }
        } else {
            emptyList()
        }
    val targets =
        remember(recent, castState.devices, castState.activeDeviceId, receivers, handoffAllowed) {
            quickCastTargets(recent, castState.devices, receivers, castState.activeDeviceId, handoffAllowed)
        }

    fun record(target: RecentCastTarget) {
        store?.let { recent = it.record(target) }
    }

    fun report(
        target: QuickCastTarget,
        failure: String?,
    ) {
        if (failure == null) {
            record(target.remembered())
            haptics.play(HapticSignal.Confirm)
        } else {
            haptics.play(HapticSignal.Reject)
            PlayerNotices.show("$failure，继续在本机播放")
        }
    }

    /** Null once the receiver has the film; otherwise why it does not. */
    suspend fun cast(target: QuickCastTarget): String? {
        val known = castManager.state.value
        if (known.devices.none { it.id == target.id }) {
            // Remembered from an earlier film and not found by this session's scan yet: look first.
            if (!known.discovering) latestRequest()
            withTimeoutOrNull(CAST_FIND_TIMEOUT_MS) {
                castManager.state.first { state -> state.devices.any { it.id == target.id } }
            } ?: return "没有找到「${target.name}」"
        }
        return if (latestCastTo(target.id)) null else castManager.state.value.error ?: "投屏失败"
    }

    /** Null once the other device has taken over; otherwise why it has not. */
    suspend fun handOff(target: QuickCastTarget): String? {
        val controller = handoff ?: return "此播放环境暂未启用账号接力服务"
        if (controller.state.value.busy) return "上一次接力还没有结束"
        controller.send(target.id)
        // send() either starts a transfer at once or refuses at once, saying why in `error`.
        withTimeoutOrNull(HANDOFF_START_TIMEOUT_MS) { controller.state.first { it.busy } }
            ?: return controller.state.value.error ?: "接力没有开始"
        return controller.state.first { !it.busy }.error
    }

    val pick =
        CastQuickPick(
            targets = targets,
            onOpen = {
                // Looks, quietly, for what the list names: a permission prompt now would end the hold.
                val now = SystemClock.elapsedRealtime()
                val current = castManager.state.value
                if (
                    !current.hasActiveSession &&
                    !current.discovering &&
                    (current.devices.isEmpty() || now - lastScanAt > QUICK_SCAN_INTERVAL_MS) &&
                    localNetworkPermissionGranted()
                ) {
                    lastScanAt = now
                    scope.launch { castManager.discover() }
                }
            },
            onPick = { target ->
                if (!target.active) {
                    scope.launch {
                        val failure = if (target.kind == QuickCastKind.Handoff) handOff(target) else cast(target)
                        report(target, failure)
                    }
                }
            },
        )
    return PlayerQuickCast(
        pick = pick,
        noteCast = { deviceId ->
            castManager.state.value.devices
                .firstOrNull { it.id == deviceId }
                ?.let { record(RecentCastTarget(castKindOf(it.id), it.id, castDisplayName(it.name))) }
        },
    )
}
