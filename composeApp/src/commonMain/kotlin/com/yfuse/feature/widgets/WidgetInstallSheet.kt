package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.widget.InstalledWidget
import com.yfuse.core.widget.WidgetRepository
import com.yfuse.core.widget.downloadWidgetSource
import com.yfuse.core.widget.rememberWidgetFileImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeText as Text

/** Import and inspection share one sheet so the component list remains visible behind it. */
@Composable
internal fun WidgetInstallSheet(
    repository: WidgetRepository,
    source: Pair<String, String>?,
    onSource: (Pair<String, String>?) -> Unit,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
) {
    var local by remember { mutableStateOf(source?.second?.let { !it.startsWith("http") } ?: false) }
    var url by remember { mutableStateOf(source?.second?.takeIf { it.startsWith("http") }.orEmpty()) }
    var candidate by remember(source) { mutableStateOf<InstalledWidget?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var inspecting by remember(source) { mutableStateOf(source != null) }
    var installing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val database by repository.database.collectAsState()
    val importFile =
        rememberWidgetFileImport(
            onFile = { text, origin -> onSource(text to origin) },
            onError = { error = it },
        )
    LaunchedEffect(source) {
        val input = source ?: return@LaunchedEffect
        inspecting = true
        try {
            candidate = repository.inspect(input.first, input.second)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "组件解析失败"
        } finally {
            inspecting = false
        }
    }
    val busy = reading || inspecting || installing
    GlassDialog(
        onDismiss = { if (!installing) onDismiss() },
        alignment = Alignment.BottomCenter,
        windowPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        animation = DialogAnimation.Lift,
        maxWidth = 560.dp,
        contentPadding = 16.dp,
        dismissEnabled = !installing,
    ) {
        val palette = LocalPalette.current
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WidgetSegmentedControl(
                options = listOf("URL", "本地"),
                selectedIndex = if (local) 1 else 0,
                onSelect = { index ->
                    if (!busy) {
                        local = index == 1
                        onSource(null)
                        error = null
                    }
                },
                modifier = Modifier.width(144.dp),
            )
            Spacer(Modifier.weight(1f))
            WidgetIconButton(AppIcons.Close, "关闭安装面板", { if (!installing) onDismiss() })
        }
        Text(
            "安装组件",
            Modifier.fillMaxWidth(),
            color = palette.text,
            style = AppTypography.section.medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        val widget = candidate
        if (widget != null) {
            WidgetMetadataCard(
                widget.metadata,
                onError = { error = it },
                onChangeSource = { onSource(null) },
                changeSourceLabel = if (local) "重新选择文件" else "更换链接",
                changeSourceEnabled = !busy,
            )
            database.widgets.firstOrNull { it.metadata.id == widget.metadata.id }?.let { old ->
                Text(
                    if (old.digest == widget.digest) "此组件已安装" else "更新 ${old.metadata.version}，保留页面配置",
                    color = palette.sub2,
                    style = AppTypography.caption.regular,
                )
            }
        } else if (local) {
            Panel {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WidgetManagerGlyph(AppIcons.Folder)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("从本地文件安装", color = palette.text, style = AppTypography.body.strong)
                        Text(
                            source?.second ?: "选择文件后，先预览组件信息。",
                            color = palette.sub2,
                            style = AppTypography.caption.regular,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            YfFormField(url, {
                url = it.take(4096)
                error = null
            }, "组件 URL", placeholder = "https://…/component.js")
        }
        if (reading || inspecting) WidgetProgress(Modifier.fillMaxWidth())
        error?.let { Text(it, color = palette.error, style = AppTypography.caption.regular) }
        WidgetPrimaryButton(
            onClick = {
                if (widget != null) {
                    scope.launch {
                        installing = true
                        error = null
                        try {
                            repository.install(widget)
                            onInstalled()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "安装失败"
                        } finally {
                            installing = false
                        }
                    }
                } else if (local) {
                    importFile()
                } else {
                    scope.launch {
                        reading = true
                        error = null
                        try {
                            onSource(downloadWidgetSource(url.trim()) to url.trim())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "读取失败"
                        } finally {
                            reading = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && (widget != null || local || url.isNotBlank()),
        ) {
            Text(
                if (installing) {
                    "正在安装…"
                } else if (widget != null) {
                    "安装组件"
                } else if (reading || inspecting) {
                    "正在读取…"
                } else if (local) {
                    "选择文件"
                } else {
                    "读取组件"
                },
            )
        }
        if (source == null && !busy) {
            WidgetTextButton({ onSource(demoWidgetSource to "builtin:demo") }, Modifier.fillMaxWidth()) {
                Text("体验示例组件", style = AppTypography.caption.regular)
            }
        }
    }
}
