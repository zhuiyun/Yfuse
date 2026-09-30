package com.yfuse.feature.handoff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.Section
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.SettingsDivider
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.handoff.ActiveHandoffPlayback
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.handoff.HandoffMedia
import com.yfuse.core.handoff.HandoffPlaybackRegistry
import com.yfuse.core.handoff.HandoffUiState
import com.yfuse.feature.profile.SettingsPage
import com.yfuse.feature.profile.rememberComposedPageStore
import com.yfuse.watch.protocol.HandoffRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Reusable on phone, tablet and TV; every action has an explicit focusable button. */
@Composable
fun DeviceHandoffScreen(
    store: Store<DeviceHandoffIntent, HandoffUiState, Nothing>,
    onBack: () -> Unit,
) {
    val state by store.states.collectAsState(store.state)
    val palette = LocalPalette.current
    // 遥控器 opens in place of this page: it belongs to the television picked here, and back returns here.
    var remoteSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var remoteName by rememberSaveable { mutableStateOf("") }
    // So does 登录服务器到电视, for a television whose 添加服务器 waits on a phone.
    var signInSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var signInName by rememberSaveable { mutableStateOf("") }
    val television = remoteSessionId
    if (television != null) {
        PhoneRemoteScreen(television, remoteName, onBack = { remoteSessionId = null })
        return
    }
    val asking = signInSessionId
    if (asking != null) {
        RemoteSignInScreen(asking, signInName, onBack = { signInSessionId = null })
        return
    }
    // Only this page, not a remote or a sign-in in its place, has the devices asked for more often.
    DisposableEffect(store) {
        store.accept(DeviceHandoffIntent.PageShown)
        onDispose { store.accept(DeviceHandoffIntent.PageHidden) }
    }
    SettingsPage(title = "设备接力", onBack = onBack) {
        if (state.signInAsks.isNotEmpty()) {
            item {
                Section(title = "等待登录的电视") {
                    SettingsCard {
                        state.signInAsks.forEachIndexed { index, waiting ->
                            if (index > 0) SettingsDivider()
                            SettingRow(
                                "登录服务器到 ${waiting.name}",
                                embedded = true,
                                onClick = {
                                    signInName = waiting.name
                                    signInSessionId = waiting.sessionId
                                },
                                icon = AppIcons.Server,
                                supporting = "电视正在等待，选一台服务器发给它",
                            )
                        }
                    }
                }
            }
        }
        item {
            Section(title = "在线设备") {
                SettingsCard {
                    SettingRow(
                        "连接状态",
                        state.connectionLabel,
                        embedded = true,
                    )
                    if (state.online && state.devices.isEmpty()) {
                        SettingsDivider()
                        SettingRow("可用设备", "暂未发现可接收的在线设备", embedded = true)
                    }
                    state.devices.forEach { device ->
                        SettingsDivider()
                        SettingRow(
                            device.name,
                            device.platform + if (state.busy) " · 接力中" else " · 接力播放",
                            embedded = true,
                            icon = AppIcons.Play,
                            onClick =
                                if (state.busy) {
                                    null
                                } else {
                                    ({ store.accept(DeviceHandoffIntent.Send(device.sessionId)) })
                                },
                        )
                    }
                    // Only televisions that host 手机遥控 are listed, and only on a device that is not one.
                    state.remotes.forEach { remote ->
                        SettingsDivider()
                        SettingRow(
                            "遥控 ${remote.name}",
                            "手机当遥控器和键盘",
                            embedded = true,
                            icon = AppIcons.Grid,
                            onClick = {
                                remoteName = remote.name
                                remoteSessionId = remote.sessionId
                            },
                        )
                    }
                    if (state.busy) {
                        SettingsDivider()
                        SettingRow("取消接力", "停止本次传输", embedded = true, onClick = {
                            store.accept(DeviceHandoffIntent.CancelTransfer)
                        })
                    }
                }
            }
        }
        item {
            Section(title = "使用说明") {
                ThemeText(
                    "两台设备需登录同一鱼服账号并保持在线。接收设备确认就绪后，本机才会暂停。",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
        }
        listOfNotNull(state.message, state.connectionError, state.error).forEach { notice ->
            item {
                ThemeText(
                    notice,
                    color = palette.sub,
                    modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
                )
            }
        }
    }
}

/**
 * The same page for the television's settings, which swap their pages in place rather than through
 * the phone's page stack; the store lives as long as the page is composed there.
 */
@Composable
fun DeviceHandoffScreen(
    controller: HandoffController,
    onBack: () -> Unit,
) {
    val store =
        rememberComposedPageStore(controller) {
            // The app's StoreFactory is this one; the television reaches the page without it.
            DeviceHandoffStoreFactory(DefaultStoreFactory(), controller).create()
        }
    DeviceHandoffScreen(store, onBack)
}

/** Mount once above the phone/TV navigation graph so incoming requests remain visible. */
@Composable
fun HandoffIncomingPrompt(controller: HandoffController) {
    val state by controller.state.collectAsState()
    state.receiveFailure?.takeIf { state.incoming == null }?.let { reason ->
        GlassDialog(onDismiss = controller::dismissReceiveFailure) {
            ThemeText("未能接收播放", style = AppTypography.section.strong, color = LocalPalette.current.text)
            Spacer(Modifier.height(Dimens.space.sm))
            ThemeText(reason, style = AppTypography.body.regular, color = LocalPalette.current.body)
            Spacer(Modifier.height(Dimens.space.lg))
            OverlayButton("知道了", onClick = controller::dismissReceiveFailure, tone = OverlayButtonTone.Primary)
        }
    }
    // Held through its exit: accepting, or a reject the server confirmed, takes the request away.
    DialogPresence(state.incoming?.takeIf { !state.busy }) { request ->
        HandoffRequestDialog(request, controller)
    }
}

/**
 * 接收播放接力？ Rejecting is a request to the account server rather than a local close, so the
 * prompt stays where it is — locked — until the server has answered. Closing first used to hide a
 * reject that then failed: the request was still pending and nothing on screen said so.
 */
@Composable
private fun HandoffRequestDialog(
    request: HandoffRequest,
    controller: HandoffController,
) {
    val palette = LocalPalette.current
    var rejecting by remember(request.id) { mutableStateOf(false) }
    var rejectFailure by remember(request.id) { mutableStateOf<String?>(null) }
    val errorBeforeReject = remember(request.id) { arrayOfNulls<String>(1) }
    val reject: () -> Unit = {
        if (!rejecting) {
            errorBeforeReject[0] = controller.state.value.error
            rejecting = true
            controller.reject(request)
        }
    }
    LaunchedEffect(request.id, rejecting) {
        if (!rejecting) return@LaunchedEffect
        // A confirmed reject takes the request away; a failed one writes the controller's error.
        // The same error twice in a row is no change the state can show, hence the time limit,
        // after which the last failure simply stays on screen.
        val answer =
            withTimeoutOrNull(REJECT_ANSWER_TIMEOUT_MS) {
                controller.state.first { it.incoming?.id != request.id || it.error != errorBeforeReject[0] }
            }
        if (answer != null && answer.incoming?.id == request.id) rejectFailure = answer.error
        rejecting = false
    }
    GlassDialog(
        onDismiss = reject,
        dismissEnabled = !rejecting,
        // The scrim and back mean 拒绝, which has to reach the server before the prompt may go.
        confirmDismiss = {
            reject()
            false
        },
    ) {
        ThemeText("接收播放接力？", style = AppTypography.section.strong, color = palette.text)
        Spacer(Modifier.height(Dimens.space.sm))
        ThemeText(
            "${request.sourceName} 请求在此设备继续观看。接收后会先检查影片是否可用。",
            style = AppTypography.body.regular.copy(lineHeight = 21.sp),
            color = palette.body,
        )
        rejectFailure?.let { failure ->
            Spacer(Modifier.height(Dimens.space.sm))
            ThemeText(
                failure,
                style = AppTypography.caption.medium,
                color = palette.error,
                modifier = Modifier.liveStatus(assertive = true),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Dimens.space.lg),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space.md),
        ) {
            OverlayButton("拒绝", onClick = reject, modifier = Modifier.weight(1f), loading = rejecting)
            OverlayButton(
                label = "接收",
                onClick = overlayAction { controller.accept(request) },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
                enabled = !rejecting,
            )
        }
    }
}

/** The account client's own request limit; past it the reject has answered or never will. */
private const val REJECT_ANSWER_TIMEOUT_MS = 15_000L

/** PlayerRoot only publishes its currently mounted source; Activity owns receiver preparation. */
@Composable
fun HandoffPlaybackBinding(
    registry: HandoffPlaybackRegistry,
    snapshot: () -> HandoffMedia?,
    pauseAndSnapshot: suspend () -> HandoffMedia?,
    resumeSource: suspend () -> Unit,
    ready: Boolean = false,
    playing: Boolean = false,
    failed: Boolean = false,
    progress: Long = 0L,
    networkBitsPerSecond: Long = 0L,
    sourceBitsPerSecond: Long = 0L,
) {
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestPause by rememberUpdatedState(pauseAndSnapshot)
    val latestResume by rememberUpdatedState(resumeSource)
    val source =
        remember(registry) {
            object : HandoffPlaybackRegistry.Source {
                override fun snapshot() = latestSnapshot()

                override suspend fun pauseAndSnapshot() = latestPause()

                override suspend fun resume() = latestResume()
            }
        }
    DisposableEffect(registry, source) {
        registry.source = source
        onDispose {
            if (registry.source === source) {
                registry.publish(source, null)
                registry.source = null
            }
        }
    }
    SideEffect {
        registry.publish(
            source,
            latestSnapshot()?.let {
                ActiveHandoffPlayback(it, ready, playing, failed, progress, networkBitsPerSecond, sourceBitsPerSecond)
            },
        )
    }
}
