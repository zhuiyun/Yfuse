package com.yfuse.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccent
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable

internal enum class SearchFilterSheet { Server, Library, Year, Genre, Status, Sort }

@Composable
internal fun SearchFilterBar(
    state: SearchState,
    onOpen: (SearchFilterSheet) -> Unit,
    onClear: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    val values = listOf(
        SearchFilterSheet.Server to (state.serverOptions.firstOrNull { it.id == state.serverId }?.label ?: "服务器"),
        SearchFilterSheet.Library to (state.libraryOptions.firstOrNull { it.id == state.libraryId }?.label ?: "媒体库"),
        SearchFilterSheet.Year to (state.year?.toString() ?: "年份"),
        SearchFilterSheet.Genre to (state.genre ?: "流派"),
        SearchFilterSheet.Status to state.watchStatus.label,
        SearchFilterSheet.Sort to state.sort.label,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(values) { (sheet, label) ->
            val active = when (sheet) {
                SearchFilterSheet.Server -> state.serverId != null
                SearchFilterSheet.Library -> state.libraryId != null
                SearchFilterSheet.Year -> state.year != null
                SearchFilterSheet.Genre -> state.genre != null
                SearchFilterSheet.Status -> state.watchStatus != SearchWatchStatus.All
                SearchFilterSheet.Sort -> state.sort != SearchSort.Relevance
            }
            Text(
                label,
                style = if (active) AppTypography.body.strong else AppTypography.body.medium,
                color = if (active) accent else palette.body,
                modifier = Modifier
                    .pressable(onClick = { onOpen(sheet) })
                    .glass(
                        shape = GlassShapes.chip,
                        fill = if (active) accent.copy(alpha = 0.13f) else palette.card2,
                        border = if (active) accent.copy(alpha = 0.28f) else palette.border,
                    )
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            )
        }
        if (state.filterCount > 0) {
            item {
                Text(
                    "清除 ${state.filterCount}",
                    style = AppTypography.body.strong,
                    color = Color.White,
                    modifier = Modifier
                        .pressable(onClick = onClear)
                        .glass(GlassShapes.chip, accent.copy(alpha = 0.82f), accent)
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
internal fun SearchFilterDialog(
    state: SearchState,
    sheet: SearchFilterSheet,
    onIntent: (SearchIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = when (sheet) {
                SearchFilterSheet.Server -> "服务器"
                SearchFilterSheet.Library -> "媒体库"
                SearchFilterSheet.Year -> "年份"
                SearchFilterSheet.Genre -> "流派"
                SearchFilterSheet.Status -> "观看状态"
                SearchFilterSheet.Sort -> "排序"
            },
            subtitle = if (sheet == SearchFilterSheet.Library && state.serverId == null) "先选择一台服务器" else null,
            onClose = onDismiss,
        )
        when (sheet) {
            SearchFilterSheet.Server -> optionList(
                listOf(SearchOption("", "全部服务器")) + state.serverOptions,
                state.serverId.orEmpty(),
            ) { onIntent(SearchIntent.SetServer(it.ifBlank { null })); onDismiss() }
            SearchFilterSheet.Library -> optionList(
                listOf(SearchOption("", "全部媒体库")) + state.libraryOptions,
                state.libraryId.orEmpty(),
            ) { onIntent(SearchIntent.SetLibrary(it.ifBlank { null })); onDismiss() }
            SearchFilterSheet.Year -> {
                OverlayOptionRow("全部年份", state.year == null, onClick = { onIntent(SearchIntent.SetYear(null)); onDismiss() })
                state.yearOptions.take(45).forEach { year ->
                    OverlayOptionRow(year.toString(), year == state.year, onClick = { onIntent(SearchIntent.SetYear(year)); onDismiss() })
                }
            }
            SearchFilterSheet.Genre -> {
                OverlayOptionRow("全部流派", state.genre == null, onClick = { onIntent(SearchIntent.SetGenre(null)); onDismiss() })
                state.genreOptions.forEach { genre ->
                    OverlayOptionRow(genre, genre == state.genre, onClick = { onIntent(SearchIntent.SetGenre(genre)); onDismiss() })
                }
            }
            SearchFilterSheet.Status -> SearchWatchStatus.entries.forEach { value ->
                OverlayOptionRow(value.label, value == state.watchStatus, onClick = { onIntent(SearchIntent.SetWatchStatus(value)); onDismiss() })
            }
            SearchFilterSheet.Sort -> SearchSort.entries.forEach { value ->
                OverlayOptionRow(value.label, value == state.sort, onClick = { onIntent(SearchIntent.SetSort(value)); onDismiss() })
            }
        }
    }
}

@Composable
private fun optionList(values: List<SearchOption>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        values.forEach { value ->
            OverlayOptionRow(value.label, value.id == selected, onClick = { onSelect(value.id) })
        }
    }
}
