package com.yfuse.feature.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yfuse.core.data.LIANGYOU_ICON_CATALOG
import com.yfuse.core.data.ServerIconCatalog
import com.yfuse.core.data.parseCatalogSources
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun ServerIconCatalogDialog(
    catalog: ServerIconCatalog,
    selectedUrl: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by catalog.state.collectAsState()
    var editingSources by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    if (editingSources) {
        ServerIconCatalogSourcesDialog(catalog, onDismiss = { editingSources = false })
        return
    }

    GlassDialog(onDismiss = onDismiss, scrollable = false) {
        val palette = LocalPalette.current
        OverlayHeader(title = "服务器图标库", onClose = onDismiss)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayButton("管理链接", { editingSources = true }, Modifier.weight(1f))
            if (state.sources.isNotEmpty()) {
                OverlayButton("刷新", catalog::refresh, Modifier.weight(1f), enabled = !state.loading)
            }
        }
        Spacer(Modifier.height(8.dp))
        YfFormField(query, { query = it }, "搜索图标名称")
        Spacer(Modifier.height(10.dp))
        val missingIcon = rememberVectorPainter(AppIcons.Server)
        val term = query.trim()
        val matches =
            remember(state.icons, term) {
                state.icons.filter { it.name.contains(term, ignoreCase = true) }.distinctBy { it.url }
            }
        if (state.failedSources.isNotEmpty()) {
            Text("${state.failedSources.size} 个图标库刷新失败", style = AppTypography.caption.regular, color = palette.error)
        }
        when {
            state.sources.isEmpty() ->
                Text(
                    "添加图标库链接后可自动匹配服务器",
                    style = AppTypography.body.regular,
                    color = palette.sub2,
                )
            state.icons.isEmpty() && state.loading ->
                Text(
                    "正在加载图标库…",
                    style = AppTypography.body.regular,
                    color = palette.sub2,
                )
            state.icons.isEmpty() -> Text("暂无可用图标", style = AppTypography.body.regular, color = palette.sub2)
            matches.isEmpty() -> Text("没有找到匹配的图标", style = AppTypography.body.regular, color = palette.sub2)
            else ->
                key(term) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(76.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(matches, key = { it.url }) { icon ->
                            val chosen = icon.url == selectedUrl
                            Column(
                                Modifier
                                    .clip(AppShapes.control)
                                    .background(palette.card2)
                                    .then(
                                        if (chosen) {
                                            Modifier.border(
                                                1.dp,
                                                LocalAccentColors.current.accent,
                                                AppShapes.control,
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ).pressable(
                                        role = Role.RadioButton,
                                        onClickLabel = "选择${icon.name}",
                                        onClick = overlayAction { onPick(icon.url) },
                                    ).semantics { selected = chosen }
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AsyncImage(
                                    model = icon.url,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                    error = missingIcon,
                                )
                                Text(
                                    icon.name,
                                    style = AppTypography.caption.regular,
                                    color = palette.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun ServerIconCatalogSourcesDialog(
    catalog: ServerIconCatalog,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(catalog.sources.joinToString("\n")) }
    val validation = remember(input) { runCatching { parseCatalogSources(input) } }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(title = "图标库链接", subtitle = "每行一个 HTTPS JSON 链接，同名图标优先使用靠前的来源。", onClose = onDismiss)
        YfFormField(input, { input = it }, "图标库链接", placeholder = "https://…", singleLine = false)
        validation.exceptionOrNull()?.message?.let { message ->
            Text(message, style = AppTypography.caption.regular, color = LocalPalette.current.error)
        }
        Spacer(Modifier.height(8.dp))
        OverlayButton("恢复默认链接", { input = LIANGYOU_ICON_CATALOG })
        OverlayButtonRow(
            dismissLabel = "取消",
            confirmLabel = "保存",
            onDismiss = onDismiss,
            confirmEnabled = validation.isSuccess,
            onConfirm =
                overlayAction {
                    catalog.saveSources(input)
                    onDismiss()
                },
        )
    }
}
