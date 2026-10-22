package com.yfuse.feature.stremio

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.designsystem.glassControlHighlight
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.stremio.StremioMeta
import com.yfuse.core.stremio.isStremioHttpUrl
import com.yfuse.feature.widgets.Panel
import com.yfuse.feature.widgets.WidgetEmptyState
import com.yfuse.feature.widgets.WidgetGroupCaption
import com.yfuse.feature.widgets.WidgetMenuRow
import com.yfuse.feature.widgets.WidgetTextButton
import com.yfuse.core.designsystem.ThemeText as Text

internal fun LazyListScope.stremioDetail(
    state: StremioScreenState,
    controller: StremioController,
) {
    val meta = state.detail ?: return
    motionItem("summary") {
        StremioInset {
            StremioMediaSummary(meta)
            WidgetTextButton({ controller.detail(meta) }, Modifier.fillMaxWidth(), enabled = !state.loading) {
                Text("刷新详情")
            }
        }
    }
    if (meta.type == "series" && meta.videos.isEmpty() && !state.loading) {
        motionItem("no-episodes") {
            StremioInset {
                WidgetEmptyState(AppIcons.EpisodeList, "暂未提供分集", "刷新详情，或安装支持这部作品的详情插件。")
            }
        }
    }
    if (meta.type != "tv" && meta.videos.isNotEmpty()) {
        motionItem("episode-heading") {
            StremioInset { WidgetGroupCaption("选择分集 · ${meta.videos.distinctBy { it.id }.size} 集") }
        }
        val videos = meta.videos.distinctBy { it.id }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        videos.forEach { video ->
            motionItem("video-${video.id}") {
                val selected = state.video?.id == video.id
                StremioInset {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        2.dp,
                                        glassControlHighlight(LocalAccentColors.current, LocalPalette.current.isDark),
                                        AppShapes.card,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Panel {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                video.thumbnail?.takeIf(::isStremioHttpUrl)?.let { thumbnail ->
                                    AsyncImage(
                                        model = thumbnail,
                                        contentDescription = null,
                                        modifier = Modifier.width(80.dp).height(50.dp).clip(AppShapes.thumb),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    WidgetMenuRow(
                                        video.label,
                                        { controller.selectVideo(video) },
                                        icon =
                                            if (video.thumbnail?.takeIf(::isStremioHttpUrl) ==
                                                null
                                            ) {
                                                AppIcons.Play
                                            } else {
                                                null
                                            },
                                        selected = selected,
                                        enabled = !state.busy,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Keep the episode and its sources together without hiding the surrounding detail.
            if (state.video?.id == video.id) stremioSources(state, controller)
        }
    } else if (state.video != null) {
        stremioSources(state, controller)
    }
}

@Composable
private fun StremioMediaSummary(meta: StremioMeta) {
    var expanded by remember(meta.id) { mutableStateOf(false) }
    var descriptionOverflows by remember(meta.id, meta.description) { mutableStateOf(false) }
    val palette = LocalPalette.current
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            val poster = meta.poster?.takeIf(::isStremioHttpUrl)
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = "${meta.name}海报",
                    modifier = Modifier.width(112.dp).height(168.dp).clip(AppShapes.card),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    ThemeIcon(
                        if (meta.type ==
                            "series"
                        ) {
                            AppIcons.Series
                        } else {
                            AppIcons.Movie
                        },
                        null,
                        tint = palette.sub2,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(meta.name, color = palette.text, style = AppTypography.section.strong)
                Text(
                    listOfNotNull(
                        stremioTypeName(meta.type),
                        meta.releaseInfo?.takeIf(String::isNotBlank),
                    ).joinToString(" · "),
                    color = palette.sub2,
                    style = AppTypography.caption.regular,
                )
                if (meta.genres.isNotEmpty()) {
                    Text(
                        meta.genres.take(10).joinToString(" / "),
                        color = palette.sub,
                        style = AppTypography.body.regular,
                    )
                }
            }
        }
        meta.description?.takeIf(String::isNotBlank)?.let { description ->
            Text(
                description.take(5_000),
                color = palette.sub,
                style = AppTypography.body.regular,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) descriptionOverflows = it.hasVisualOverflow },
            )
            if (expanded || descriptionOverflows) {
                WidgetTextButton({ expanded = !expanded }) { Text(if (expanded) "收起简介" else "展开简介") }
            }
        }
    }
}

internal fun LazyListScope.stremioSources(
    state: StremioScreenState,
    controller: StremioController,
) {
    // A source group belongs to one video. Reusing its keys across episodes would move
    // LazyColumn's scroll anchor to the new episode and hide the row just selected.
    val videoId = state.video?.id ?: return
    val sourceKey = "sources:${videoId.length}:$videoId"
    motionItem("$sourceKey:heading") {
        StremioInset {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "播放源",
                    modifier = Modifier.weight(1f),
                    style = AppTypography.body.strong,
                    color = LocalPalette.current.text,
                )
                WidgetTextButton(
                    { state.video?.let(controller::selectVideo) },
                    enabled = !state.loading,
                ) {
                    Text(if (state.failures.isNotEmpty() || state.message != null) "重试" else "刷新")
                }
            }
            Text(
                if (state.loading) "正在加载播放源…" else "选择可用的播放源开始观看",
                style = AppTypography.caption.regular,
                color = LocalPalette.current.sub2,
            )
        }
    }
    motionItems(state.sources.withIndex().toList(), key = { "$sourceKey:stream:${it.index}" }) { indexed ->
        val source = indexed.value
        val stream = source.stream
        val reason = stream.unsupportedReason
        StremioInset {
            Panel {
                WidgetMenuRow(
                    stream.name,
                    { controller.play(source) },
                    icon = if (reason == null) AppIcons.Play else AppIcons.Info,
                    subtitle = source.addonName,
                    value = if (reason == null) "播放" else null,
                    enabled = !state.loading && reason == null,
                )
                (stream.description ?: stream.title)?.takeIf(String::isNotBlank)?.let {
                    Text(it.take(1200), color = LocalPalette.current.sub, style = AppTypography.caption.regular)
                }
                reason?.let { Text(it, color = LocalPalette.current.sub2, style = AppTypography.caption.regular) }
            }
        }
    }
    if (!state.loading && state.sources.isEmpty()) {
        motionItem("$sourceKey:empty") {
            StremioInset {
                val failed = state.failures.isNotEmpty() || state.message != null
                WidgetEmptyState(
                    AppIcons.PlaybackSource,
                    if (failed) "播放源暂未加载" else "还没有可用播放源",
                    if (failed) "点击上方重试，重新加载播放源。" else "目录提供影片信息，播放需要支持这部作品的播放源插件。",
                )
            }
        }
    }
}
