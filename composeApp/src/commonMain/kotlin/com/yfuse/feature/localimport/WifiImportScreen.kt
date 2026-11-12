package com.yfuse.feature.localimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.localimport.WifiImportedFile
import com.yfuse.core.localimport.createWifiImportService
import com.yfuse.core.network.localNetworkConnectionsRestricted
import com.yfuse.core.network.localNetworkPermissionGranted
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.externalPlaybackItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun WifiImportEntry() {
    var open by remember { mutableStateOf(false) }
    OverlayActionRow(label = "Wi-Fi 导入视频", description = "从同一局域网浏览器传到本机", onClick = { open = true })
    if (open) WifiImportDialog(onDismiss = { open = false })
}

@Composable
private fun WifiImportDialog(onDismiss: () -> Unit) {
    val service = remember { createWifiImportService() }
    val state by service.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember { mutableStateOf<String?>(null) }
    var launch by remember { mutableStateOf<PlayerMediaItem?>(null) }
    var removing by remember { mutableStateOf<WifiImportedFile?>(null) }
    val palette = LocalPalette.current

    fun start() {
        scope.launch {
            try {
                service.start()
                error = null
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                error = "无法开启接收，请连接 Wi-Fi 并允许本地网络访问"
            }
        }
    }
    val requestPermission =
        rememberLocalNetworkPermissionRequest(onGranted = { start() }, onDenied = {
            error =
                "需要本地网络权限才能接收文件"
        })
    LaunchedEffect(Unit) {
        try {
            service.load()
        } catch (
            cancel: CancellationException,
        ) {
            throw cancel
        } catch (_: Exception) {
            error = "无法读取本机导入列表"
        }
    }
    DisposableEffect(lifecycle, service) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) service.stop() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            service.stop()
        }
    }
    GlassDialog(onDismiss = onDismiss, scrollable = false) {
        OverlayHeader(title = "Wi-Fi 导入视频", subtitle = "开启后，在同一局域网的浏览器访问地址并输入配对码。离开页面或进入后台会关闭接收。", onClose = onDismiss)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                if (state.running) {
                    Text(state.address.orEmpty(), style = AppTypography.body.strong, color = palette.text)
                    Text("配对码：${state.pairingCode}", style = AppTypography.section.strong, color = palette.text)
                    TextButton(onClick = service::stop) { Text("停止接收") }
                } else {
                    TextButton(onClick = {
                        if (localNetworkConnectionsRestricted() &&
                            !localNetworkPermissionGranted()
                        ) {
                            requestPermission()
                        } else {
                            start()
                        }
                    }) { Text("开启临时接收") }
                }
                Text(
                    "单文件 2 GiB · 本次 4 GiB · 本机总计 8 GiB / 200 个文件",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
                (error ?: state.message)?.let { Text(it, style = AppTypography.caption.regular, color = palette.sub2) }
            }
            items(state.files, key = { it.id }) { file ->
                Row(Modifier.fillMaxWidth()) {
                    OverlayActionRow(
                        label = file.name,
                        description = "${file.sizeBytes / 1024 / 1024} MB · 已存入本机",
                        modifier =
                            Modifier
                                .weight(
                                    1f,
                                ),
                        onClick = {
                            service.stop()
                            launch = externalPlaybackItem(file.uri, file.name)
                        },
                    )
                    TextButton(enabled = !state.receiving, onClick = { removing = file }) { Text("移除") }
                }
            }
            if (state.files.isEmpty()) item { Text("尚未接收视频", color = palette.sub2) }
        }
    }
    removing?.let { file ->
        ConfirmDialog(
            title = "移除本地视频",
            message = "删除接收到的「${file.name}」？",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                removing = null
                scope.launch {
                    try {
                        service.remove(file.id)
                    } catch (_: Exception) {
                        error =
                            "无法移除文件"
                    }
                }
            },
            onDismiss = { removing = null },
        )
    }
    launch?.let { item ->
        PlayerLauncher(items = listOf(item), startIndex = 0, startPositionMs = 0, onLaunched = {
            launch =
                null
        })
    }
}
