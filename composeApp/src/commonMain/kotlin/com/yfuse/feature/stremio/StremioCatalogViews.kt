package com.yfuse.feature.stremio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.stremio.StremioExtra
import com.yfuse.core.stremio.isStremioHttpUrl
import com.yfuse.feature.widgets.Panel
import com.yfuse.feature.widgets.WidgetEmptyState
import com.yfuse.feature.widgets.WidgetGroupCaption
import com.yfuse.feature.widgets.WidgetMenuRow
import com.yfuse.feature.widgets.WidgetPrimaryButton
import com.yfuse.feature.widgets.WidgetSecondaryButton
import com.yfuse.feature.widgets.WidgetSheet
import com.yfuse.core.designsystem.ThemeText as Text

internal fun LazyListScope.stremioCatalog(
    state: StremioScreenState,
    controller: StremioController,
) {
    val ref = state.catalog ?: return
    motionItem("filters") { StremioInset { StremioCatalogFilters(state, controller) } }
    motionItem("catalog-label") {
        StremioInset { WidgetGroupCaption("${ref.addon.manifest.name} · ${state.items.size} 项内容") }
    }
    motionItems(state.items.chunked(3), key = { "posters-${it.first().type}-${it.first().id}" }) { row ->
        StremioInset {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { meta ->
                    CaptionedPoster(
                        url = meta.poster?.takeIf(::isStremioHttpUrl),
                        title = meta.name,
                        year = meta.releaseInfo,
                        onClick = { controller.open(meta) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (!state.loading && state.items.isEmpty()) {
        motionItem("empty") {
            StremioInset {
                WidgetEmptyState(AppIcons.Search, "没有找到内容", "试试其他关键词或筛选条件。")
            }
        }
    }
    if (state.canLoadMore) {
        motionItem("more") {
            StremioInset {
                WidgetSecondaryButton(
                    { controller.browse(more = true) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.loading,
                ) {
                    Text(if (state.loading) "正在加载…" else "加载更多")
                }
            }
        }
    }
}

@Composable
private fun StremioCatalogFilters(
    state: StremioScreenState,
    controller: StremioController,
) {
    val ref = state.catalog ?: return
    val extras = ref.catalog.extras.filterNot { it.name == "skip" }
    var open by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf<StremioExtra?>(null) }
    var draft by remember(state.extras) { mutableStateOf(state.extras) }
    Panel {
        WidgetMenuRow(
            if (extras.isEmpty()) "刷新目录" else "搜索与筛选",
            { if (extras.isEmpty()) controller.browse() else open = true },
            icon = if (extras.isEmpty()) AppIcons.Refresh else AppIcons.Search,
            subtitle =
                state.extras
                    .filterKeys {
                        it != "skip"
                    }.values
                    .filter(String::isNotBlank)
                    .joinToString(" · ")
                    .ifBlank { "浏览全部内容" },
            enabled = !state.loading,
        )
    }
    if (open) {
        WidgetSheet("搜索与筛选", {
            open = false
            draft = state.extras
        }) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                extras.forEach { extra ->
                    val value = draft[extra.name].orEmpty()
                    val title = filterTitle(extra) + if (extra.isRequired) " · 必填" else ""
                    if (extra.options.isEmpty()) {
                        YfFormField(value, { draft = draft + (extra.name to it) }, title)
                    } else {
                        WidgetMenuRow(title, { selecting = extra }, value = value.ifBlank { "全部" })
                    }
                }
            }
            WidgetPrimaryButton({
                extras.forEach { extra ->
                    if (state.extras[extra.name].orEmpty() != draft[extra.name].orEmpty()) {
                        controller.filter(extra.name, draft[extra.name].orEmpty())
                    }
                }
                controller.browse()
                open = false
            }, Modifier.fillMaxWidth(), enabled = ref.catalog.accepts(draft)) { Text("查看结果") }
        }
    }
    selecting?.let { extra ->
        WidgetSheet(filterTitle(extra), { selecting = null }) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (!extra.isRequired) {
                    WidgetMenuRow("全部", {
                        draft = draft + (extra.name to "")
                        selecting = null
                    }, selected = draft[extra.name].isNullOrBlank())
                }
                extra.options.forEach { option ->
                    WidgetMenuRow(option, {
                        draft = draft + (extra.name to option)
                        selecting = null
                    }, selected = draft[extra.name] == option)
                }
            }
        }
    }
}

private fun filterTitle(extra: StremioExtra): String =
    when (extra.name) {
        "search" -> "搜索当前目录"
        "genre" -> "类型"
        else -> extra.name
    }
