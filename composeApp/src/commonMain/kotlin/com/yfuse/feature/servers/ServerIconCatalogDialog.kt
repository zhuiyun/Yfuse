package com.yfuse.feature.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import com.yfuse.core.data.ServerCatalogIcon
import com.yfuse.core.data.ServerIconCatalog
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import kotlinx.coroutines.CancellationException
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun ServerIconCatalogDialog(
    catalog: ServerIconCatalog,
    selectedUrl: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var icons by remember(catalog) { mutableStateOf(emptyList<ServerCatalogIcon>()) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(catalog, attempt) {
        failed = false
        try {
            icons = catalog.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    GlassDialog(onDismiss = onDismiss, scrollable = false) {
        val palette = LocalPalette.current
        OverlayHeader(title = "良友媒体图标库", onClose = onDismiss)
        YfFormField(query, { query = it }, "搜索图标名称")
        Spacer(Modifier.height(10.dp))
        val missingIcon = rememberVectorPainter(AppIcons.Server)
        val term = query.trim()
        val matches =
            remember(icons, term) {
                if (term.isEmpty()) icons else icons.filter { it.name.contains(term, ignoreCase = true) }
            }
        when {
            failed -> {
                Text("图标库加载失败，请检查网络", style = AppTypography.body.regular, color = palette.sub2)
                OverlayButton("重新加载", { attempt++ })
            }
            icons.isEmpty() -> Text("正在加载图标库…", style = AppTypography.body.regular, color = palette.sub2)
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
                                    modifier = Modifier.size(48.dp).clip(AppShapes.thumb),
                                    contentScale = ContentScale.Fit,
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
