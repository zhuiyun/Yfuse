package com.yfuse.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.YfChip
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.glassControlHighlight
import com.yfuse.core.designsystem.pressableTarget
import com.yfuse.core.designsystem.selectionColor
import com.yfuse.core.designsystem.waitingPulse
import com.yfuse.core.model.LibraryResolution
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun LibraryGridOptionsButton(
    filterCount: Int,
    pending: Boolean,
    onOpen: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val filtered = filterCount > 0
    val action = if (filtered) "打开列表选项，已选 $filterCount 项筛选" else "打开列表选项"
    Row(
        Modifier
            .pressableTarget(AppShapes.pill, onClickLabel = action, onClick = onOpen)
            .glass(
                AppShapes.pill,
                if (filtered) accent.container else palette.glassStrong,
                if (filtered) glassControlHighlight(accent, palette.isDark) else palette.tabbarBorder,
            ).waitingPulse(active = pending, shape = AppShapes.pill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (filtered) "筛选 $filterCount" else "选项",
            style = AppTypography.body.strong,
            color = if (filtered) selectionColor(accent.accent) else palette.text,
            maxLines = 1,
        )
    }
}

@Composable
internal fun LibraryGridOptionsDialog(
    state: GridState,
    gridDensity: GridDensityState,
    onIntent: (GridIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(title = "列表选项", onClose = onDismiss)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.sortable) {
                LibraryGridOptionGroup("排序") {
                    items(LibrarySort.entries, key = { it.name }) { option ->
                        YfChip(
                            label = gridSortLabel(option),
                            selected = state.sort == option,
                            onClick = { onIntent(GridIntent.SetSort(option)) },
                            role = Role.RadioButton,
                        )
                    }
                }
            } else if (state.containerKind == MediaContainerKind.Playlist) {
                Text(
                    "按服务器中的手工顺序显示",
                    style = AppTypography.caption.medium,
                    color = LocalPalette.current.sub2,
                )
            }
            if (state.genres.isNotEmpty() || state.tags.isNotEmpty()) {
                LibraryGridOptionGroup("分类") {
                    item(key = "all") {
                        YfChip(
                            label = "全部",
                            selected = state.genre == null && state.tag == null,
                            onClick = { onIntent(GridIntent.SetGenre(null)) },
                            onClickLabel = "选择分类 全部",
                            role = Role.RadioButton,
                        )
                    }
                    items(state.genres, key = { "genre:$it" }) { genre ->
                        YfChip(
                            label = genre,
                            selected = state.genre == genre,
                            onClick = { onIntent(GridIntent.SetGenre(genre)) },
                            onClickLabel = "选择分类 $genre",
                            role = Role.RadioButton,
                        )
                    }
                    items(state.tags, key = { "tag:$it" }) { tag ->
                        YfChip(
                            label = "#$tag",
                            selected = state.tag == tag,
                            onClick = { onIntent(GridIntent.SetTag(tag)) },
                            onClickLabel = "选择标签 $tag",
                            role = Role.RadioButton,
                        )
                    }
                }
            } else if (state.genresLoading) {
                Text("正在加载分类…", style = AppTypography.caption.medium, color = LocalPalette.current.sub2)
            }
            state.genreLoadError?.let { message ->
                OverlayActionRow("重试分类", { onIntent(GridIntent.RetryGenres) }, description = message)
            }
            if (state.resolutionFilterable) {
                LibraryGridOptionGroup("规格") {
                    items(LibraryResolution.entries, key = { it.name }) { resolution ->
                        YfChip(
                            label = resolution.label,
                            selected = state.resolution == resolution,
                            onClick = { onIntent(GridIntent.SetResolution(resolution)) },
                            onClickLabel = "选择规格 ${resolution.label}",
                            role = Role.RadioButton,
                        )
                    }
                }
            }
            if (state.unplayedFilterable) {
                YfChip(
                    label = "只看未看",
                    selected = state.unplayedOnly,
                    onClick = { onIntent(GridIntent.SetUnplayedOnly(!state.unplayedOnly)) },
                    role = Role.Checkbox,
                )
            }
            if (state.loadedCount > 0) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "海报大小 · 每行 ${gridDensity.shownColumns} 张",
                        modifier = Modifier.weight(1f),
                        style = AppTypography.body.strong,
                        color = LocalPalette.current.text,
                    )
                    GridDensityButtons(gridDensity)
                }
            }
        }
    }
}

@Composable
private fun LibraryGridOptionGroup(
    title: String,
    choices: LazyListScope.() -> Unit,
) {
    Column {
        Text(title, style = AppTypography.body.strong, color = LocalPalette.current.text)
        LazyRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = choices,
        )
    }
}

private fun gridSortLabel(sort: LibrarySort): String =
    when (sort) {
        LibrarySort.RecentlyAdded -> "最近添加"
        LibrarySort.Name -> "名称"
        LibrarySort.Year -> "年份"
        LibrarySort.Rating -> "评分"
    }
