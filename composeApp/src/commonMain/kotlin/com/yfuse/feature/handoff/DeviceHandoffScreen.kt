package com.yfuse.feature.handoff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.handoff.ActiveHandoffPlayback
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.handoff.HandoffMedia
import com.yfuse.core.handoff.HandoffPlaybackRegistry

/** Reusable on phone, tablet and TV; every action has an explicit focusable button. */
@Composable
fun DeviceHandoffScreen(
    controller: HandoffController,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThemeText("接力到另一台设备", style = AppTypography.section.strong, color = palette.text)
        ThemeText("两台设备需登录同一鱼服账号并保持在线。接收设备确认就绪后，本机才会暂停。", style = AppTypography.body.regular, color = palette.body)
        if (!state.online) ThemeText("请先登录账号并连接接力服务", color = palette.body)
        if (state.online && state.devices.isEmpty()) ThemeText("暂未发现可接收的在线设备", color = palette.body)
        state.devices.forEach { device ->
            YfButton(
                "${device.name} · ${device.platform}",
                { controller.send(device.sessionId) },
                enabled = !state.busy,
            )
        }
        state.message?.let { ThemeText(it, color = palette.body) }
        state.connectionError?.let { ThemeText(it, color = palette.body) }
        state.error?.let { ThemeText(it, color = palette.body) }
        if (state.busy) YfButton("取消接力", controller::cancelTransfer, tone = YfButtonTone.Secondary)
        YfButton("返回", onBack, tone = YfButtonTone.Secondary)
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
