package com.yfuse.feature.stremio

import androidx.compose.foundation.lazy.LazyListScope
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.stremio.StremioVideo

internal fun LazyListScope.stremioDetail(
    state: StremioScreenState,
    controller: StremioController,
) {
    val meta = state.detail ?: return
    motionItem("summary") {
        StremioInset {
            PageHint(
                listOfNotNull(
                    meta.releaseInfo,
                    meta.genres.take(10).joinToString(" / ").takeIf {
                        it.isNotBlank()
                    },
                    meta.description,
                ).joinToString("\n").take(5_000),
            )
            YfButton("刷新详情", { controller.detail(meta) }, enabled = !state.loading, tone = YfButtonTone.Secondary)
            if (meta.type != "series" && (meta.videos.isEmpty() || meta.type == "tv")) {
                YfButton(
                    "查找播放源",
                    { controller.selectVideo(StremioVideo(meta.id, meta.name)) },
                    enabled = !state.loading,
                )
            }
            if (meta.type == "series" && meta.videos.isEmpty() && !state.loading) {
                PageHint("插件没有提供分集，请刷新或安装支持此作品的详情插件。")
            }
        }
    }
    if (meta.type !=
        "tv"
    ) {
        motionItems(
            meta.videos
                .distinctBy {
                    it.id
                }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })),
            key = { "video-${it.id}" },
        ) { video ->
            StremioInset {
                SettingRow(
                    video.label,
                    onClick = { controller.selectVideo(video) },
                    enabled = !state.loading,
                )
            }
        }
    }
}

internal fun LazyListScope.stremioSources(
    state: StremioScreenState,
    controller: StremioController,
) {
    motionItem("retry-streams") {
        StremioInset {
            YfButton("重新查找播放源", {
                state.video?.let(controller::selectVideo)
            }, enabled = !state.loading, tone = YfButtonTone.Secondary)
        }
    }
    motionItems(state.sources.withIndex().toList(), key = { "stream-${it.index}" }) { indexed ->
        val source = indexed.value
        val stream = source.stream
        StremioInset {
            SettingRow(
                "${source.addonName} · ${stream.name}",
                supporting =
                    stream.description ?: stream.title ?: "HTTP 播放源",
                onClick = { controller.play(source) },
                enabled = !state.loading,
            )
            stream.unsupportedReason?.let { PageHint(it) }
        }
    }
    if (!state.loading && state.sources.isEmpty()) {
        motionItem("no-streams") {
            StremioInset { PageHint("没有找到播放源。目录插件只提供影片信息，请安装支持此作品的播放源插件。") }
        }
    }
}
