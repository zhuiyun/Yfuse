package com.yfuse.feature.stremio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfChip
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.stremio.isStremioHttpUrl

internal fun LazyListScope.stremioCatalog(
    state: StremioScreenState,
    controller: StremioController,
) {
    val ref = state.catalog ?: return
    motionItem("filters") {
        StremioInset {
            ref.catalog.extras.filterNot { it.name == "skip" }.forEach { extra ->
                val value = state.extras[extra.name].orEmpty()
                val label =
                    when (extra.name) {
                        "search" -> "搜索当前目录"
                        "genre" -> "类型"
                        else -> extra.name
                    }
                YfFormField(value, { controller.filter(extra.name, it) }, label + if (extra.isRequired) "（必填）" else "")
                if (extra.options.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        extra.options.forEach { option ->
                            YfChip(option, value == option, { controller.filter(extra.name, option) })
                        }
                    }
                }
            }
            YfButton("加载目录／搜索", { controller.browse() }, enabled = !state.loading)
        }
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
    if (!state.loading && state.items.isEmpty()) motionItem("empty") { StremioInset { PageHint("暂无内容，可调整筛选条件或搜索词。") } }
    if (state.canLoadMore) {
        motionItem("more") {
            StremioInset { YfButton("加载更多", { controller.browse(more = true) }, enabled = !state.loading) }
        }
    }
}
