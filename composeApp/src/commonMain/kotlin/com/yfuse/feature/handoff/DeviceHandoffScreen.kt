package com.yfuse.feature.handoff

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.handoff.ActiveHandoffPlayback
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.handoff.HandoffMedia
import com.yfuse.core.handoff.HandoffPlaybackRegistry
import com.yfuse.feature.profile.Section
import com.yfuse.feature.profile.SettingRow
import com.yfuse.feature.profile.SettingsCard
import com.yfuse.feature.profile.SettingsDivider
import com.yfuse.feature.profile.SettingsPage

/** Reusable on phone, tablet and TV; every action has an explicit focusable button. */
@Composable
fun DeviceHandoffScreen(
    controller: HandoffController,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val palette = LocalPalette.current
    SettingsPage(title = "设备接力", onBack = onBack) {
        item {
            Section(title = "在线设备") {
                SettingsCard {
                    SettingRow(
                        "连接状态",
                        if (state.online) "已连接" else "请先登录账号并连接接力服务",
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
                            device.platform + if (state.busy) " · 接力中" else " · 接力播放 ›",
                            embedded = true,
                            icon = AppIcons.Play,
                            onClick = if (state.busy) null else ({ controller.send(device.sessionId) }),
                        )
                    }
                    if (state.busy) {
                        SettingsDivider()
                        SettingRow("取消接力", "停止本次传输", embedded = true, onClick = controller::cancelTransfer)
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

/** Mount once above the phone/TV navigation graph so incoming requests remain visible. */
@Composable
fun HandoffIncomingPrompt(controller: HandoffController) {
    val state by controller.state.collectAsState()
    val request = state.incoming ?: return
    if (!state.busy) {
        ConfirmDialog(
            title = "接收播放接力？",
            message = "${request.sourceName} 请求在此设备继续观看。接收后会先检查影片是否可用。",
            confirmLabel = "接收",
            dismissLabel = "拒绝",
            onConfirm = { controller.accept(request) },
            onDismiss = { controller.reject(request) },
        )
    }
}

/** PlayerRoot only publishes its currently mounted source; Activity owns receiver preparation. */
@Composable
fun HandoffPlaybackBinding(
    registry: HandoffPlaybackRegistry,
    snapshot: () -> HandoffMedia?,
    pauseAndSnapshot: suspend () -> HandoffMedia?,
    resumeSource: suspend () -> Unit,
    ready: Boolean = false,
    playing: Boolean = false,
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
    SideEffect { registry.publish(source, latestSnapshot()?.let { ActiveHandoffPlayback(it, ready, playing) }) }
}
