package com.yfuse.feature.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.glass
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.toUserMessage
import com.yfuse.feature.library.LibraryAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun SourceSetupGuide(
    server: SavedServer?,
    onAdd: () -> Unit,
    check: suspend (String) -> Result<String>,
    onOpenLibrary: () -> Unit,
) {
    var expanded by remember { mutableStateOf(server == null) }
    var busy by remember(server?.id) { mutableStateOf(false) }
    var result by remember(server?.id) { mutableStateOf<String?>(null) }
    var checkedServerId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    LaunchedEffect(server?.id) { checkedServerId = server?.id }
    Column(
        Modifier.fillMaxWidth().glass(GlassShapes.card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryAction(if (expanded) "收起接入引导" else "连接遇到问题？查看接入引导") { expanded = !expanded }
        if (expanded) {
            Text("1 · 添加媒体来源", style = AppTypography.body.strong)
            Text(
                "媒体服务器账号用于访问影片；Yfuse 账号用于同步资料和设备功能。两种账号可以分别登录。支持 Emby、Jellyfin 和 Plex。",
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
            LibraryAction(if (server == null) "添加服务器 / 快速连接" else "添加其他服务器", onClick = onAdd)
            Text("2 · 检查当前连接和媒体库权限", style = AppTypography.body.strong)
            if (server != null) {
                Text("${server.serverName} · ${server.userName}", style = AppTypography.caption.regular)
                LibraryAction(if (busy) "检查中…" else "检查连接与媒体库") {
                    if (!busy) {
                        val requestedId = server.id
                        busy = true
                        scope.launch {
                            try {
                                val response = check(requestedId)
                                if (checkedServerId == requestedId) {
                                    result =
                                        response.fold({ it }, { error ->
                                            if (error is CancellationException) throw error
                                            "${error.toUserMessage("无法连接服务器，请重试")}。可在服务器菜单中重新登录，或检查地址、HTTPS 与备用线路。"
                                        })
                                }
                            } finally {
                                if (checkedServerId == requestedId) busy = false
                            }
                        }
                    }
                }
            } else {
                Text("添加服务器后可检查此步骤。", style = AppTypography.caption.regular, color = palette.sub2)
            }
            result?.let { Text(it, style = AppTypography.caption.regular, color = palette.sub2) }
            Text("3 · 浏览并选择适合设备的版本", style = AppTypography.body.strong)
            Text(
                "进入详情比较片源、码率、音轨和字幕。网络较慢可换备用线路或较低码率的已有版本；连接检查不代表格式兼容性已验证。",
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
            if (server != null) LibraryAction("浏览媒体库", onClick = onOpenLibrary)
            LibraryAction("稍后再看") { expanded = false }
        }
    }
}
