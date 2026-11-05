package com.yfuse.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun rememberFileSourceSearch(
    component: SearchHomeComponent,
    state: SearchState,
    sourceFilter: String,
    allowed: Boolean,
): FileSourceSearchPage {
    val sources by component.fileSources.sources.collectAsState()
    val libraries by component.fileSources.scans.libraries
        .collectAsState()
    val progress by component.fileSources.progress.collectAsState()
    // Playback progress/server loading ticks do not restart a local filename query.
    val criteria =
        listOf(
            state.query,
            state.type,
            state.year,
            state.watchStatus,
            state.sort,
            state.serverId,
            state.libraryId,
            state.genre,
            state.person,
            state.playlistName,
        )
    val result by produceState(
        FileSourceSearchPage(),
        criteria,
        listOf(sources, libraries, progress, sourceFilter, allowed),
    ) {
        value = FileSourceSearchPage()
        if (!allowed || sourceFilter == "servers") return@produceState
        delay(200)
        value =
            withContext(Dispatchers.Default) {
                searchFileSourceIndex(
                    state,
                    sources,
                    libraries,
                    progress,
                    selectedSource = sourceFilter.removePrefix("source:").takeIf { sourceFilter.startsWith("source:") },
                )
            }
    }
    return result
}

@Composable
internal fun FileSourceSearchResult(
    hit: FileSourceSearchHit,
    onPlay: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .pressable(role = Role.Button, onClick = onPlay)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            hit.name,
            style = AppTypography.body.strong,
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${hit.source.name} · ${hit.source.kind.label} · ${hit.file.path.last()}",
            style = AppTypography.caption.medium,
            color = palette.sub2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun FileSourceSearchCoverage(
    page: FileSourceSearchPage,
    recentlyAdded: Boolean,
) {
    val palette = LocalPalette.current
    Column(
        Modifier.padding(horizontal = Dimens.pageHorizontal, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("文件来源 · ${page.matches} 个文件", style = AppTypography.body.strong, color = palette.text)
        val note =
            when {
                page.unsupportedFilter -> "当前人物、片单、服务器、媒体库或风格筛选不适用于文件索引，请清除这些筛选后搜索。"
                else ->
                    buildList {
                        add("搜索已扫描的标题与文件名；点选后解析最新播放地址。")
                        if (page.unindexedSources > 0) add("${page.unindexedSources} 个来源尚无有效索引，请到文件来源扫描。")
                        if (page.partialSources > 0) add("${page.partialSources} 个来源扫描未完成，结果可能不全。")
                        if (page.matches > page.hits.size) add("显示前 ${page.hits.size} 项，请缩小关键词。")
                        if (recentlyAdded) add("文件索引未记录添加时间，按索引顺序显示。")
                    }.joinToString(" ")
            }
        Text(note, style = AppTypography.caption.medium, color = palette.sub2)
    }
}
