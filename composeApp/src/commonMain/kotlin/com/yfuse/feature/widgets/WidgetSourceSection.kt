package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.hasStreamResolver
import com.yfuse.core.widget.isWidgetFilmOrSeries
import com.yfuse.core.widget.openWidgetVideo
import com.yfuse.core.widget.requiresWidgetDetailLookup
import com.yfuse.core.widget.widgetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import com.yfuse.core.designsystem.ThemeText as Text

/** Source routes travel with a title into the standard details screen; opening it never plays. */
@Composable
internal fun WidgetSourceSection(context: WidgetSourceContext) {
    val repository = remember { widgetRepository() }
    val database by repository.database.collectAsState()
    val hasStreamResolver = context.hasStreamResolver(database)
    if (!context.media.hasWidgetPlaybackSource() && !hasStreamResolver) return
    val scope = rememberCoroutineScope()
    var media by remember(context) { mutableStateOf(context.media) }
    var busy by remember(context) { mutableStateOf(context.media.requiresWidgetDetailLookup()) }
    var error by remember(context) { mutableStateOf<String?>(null) }
    var retry by remember(context) { mutableIntStateOf(0) }
    var launching by remember(context) { mutableStateOf(false) }
    var resolvedChoices by remember(context) { mutableStateOf<List<WidgetMedia>?>(null) }
    var failedRoute by remember(context) { mutableStateOf<WidgetMedia?>(null) }
    val selectEpisode =
        hasStreamResolver && media.isWidgetFilmOrSeries() &&
            media.mediaType.trim().lowercase() in setOf("tv", "series") &&
            media.episode == null && media.children.isEmpty()
    var seasonInput by remember(context, media.season) { mutableStateOf((media.season ?: 1).toString()) }
    var episodeInput by remember(context) { mutableStateOf("1") }
    val selectedSeason = seasonInput.toIntOrNull()?.takeIf { it > 0 }
    val selectedEpisode = episodeInput.toIntOrNull()?.takeIf { it > 0 }
    val validEpisode = !selectEpisode || (selectedSeason != null && selectedEpisode != null)
    val routes =
        resolvedChoices ?: remember(media, hasStreamResolver) {
            media.widgetPlaybackRoutes().ifEmpty { if (hasStreamResolver) listOf(media) else emptyList() }
        }

    fun play(route: WidgetMedia) {
        if (launching || !validEpisode) return
        val selectedRoute =
            if (selectEpisode && resolvedChoices == null) {
                route.copy(season = selectedSeason, episode = selectedEpisode)
            } else {
                route
            }
        launching = true
        error = null
        scope.launch {
            try {
                val choices =
                    if (resolvedChoices != null && route.videoUrl.isNotBlank()) {
                        listOf(route)
                    } else {
                        repository.playback(context, selectedRoute).widgetPlaybackRoutes()
                    }
                require(choices.isNotEmpty()) { "组件未返回播放地址" }
                if (choices.size == 1 && choices[0].videoUrl.isNotBlank()) {
                    openWidgetVideo(choices[0])
                    delay(1000)
                } else {
                    resolvedChoices = choices
                }
                failedRoute = null
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedRoute = selectedRoute
                error = widgetFailureMessage(e.message.orEmpty())
            } finally {
                launching = false
            }
        }
    }
    LaunchedEffect(context, retry) {
        if (!context.media.requiresWidgetDetailLookup()) return@LaunchedEffect
        busy = true
        try {
            val loaded = repository.detail(context.widgetId, context.media, forceRefresh = retry > 0)
            media = loaded.copy(children = loaded.children.ifEmpty { context.media.children })
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = widgetFailureMessage(e.message.orEmpty())
        } finally {
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("组件播放来源", Modifier.weight(1f), style = AppTypography.section.strong, color = LocalPalette.current.text)
        }
        if (selectEpisode) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                YfFormField(
                    value = seasonInput,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all { it in '0'..'9' }) {
                            seasonInput = value
                            resolvedChoices = null
                            failedRoute = null
                            error = null
                        }
                    },
                    label = "第几季",
                    modifier = Modifier.weight(1f),
                    enabled = !busy && !launching,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                YfFormField(
                    value = episodeInput,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all { it in '0'..'9' }) {
                            episodeInput = value
                            resolvedChoices = null
                            failedRoute = null
                            error = null
                        }
                    },
                    label = "第几集",
                    modifier = Modifier.weight(1f),
                    enabled = !busy && !launching,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
        if (routes.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(routes) { _, route ->
                    WidgetSecondaryButton(
                        enabled = !launching && validEpisode && (!busy || route.videoUrl.isNotBlank()),
                        onClick = { play(route) },
                    ) {
                        Text(
                            if (hasStreamResolver && resolvedChoices == null && routes.size == 1) {
                                "查找资源"
                            } else {
                                route.title.ifBlank { "播放" }
                            },
                            Modifier.widthIn(max = 220.dp),
                            style = AppTypography.body.medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        val hint =
            error ?: when {
                busy -> "正在加载组件播放来源…"
                launching -> "正在查找组件资源…"
                !validEpisode -> "请填写大于 0 的季数和集数。"
                routes.isEmpty() -> "此组件暂未返回可播放来源。"
                else -> null
            }
        hint?.let {
            Text(it, color = LocalPalette.current.sub, style = AppTypography.caption.regular, maxLines = 3)
        }
        if (error != null) WidgetRetryButton({ failedRoute?.let(::play) ?: run { retry++ } }, busy = busy || launching)
    }
}

internal fun WidgetMedia.hasWidgetPlaybackSource(): Boolean =
    videoUrl.isNotBlank() ||
        children.isNotEmpty() ||
        requiresWidgetDetailLookup()

/** Flatten source groups into a bounded horizontal rail, retaining inherited request headers. */
internal fun WidgetMedia.widgetPlaybackRoutes(): List<WidgetMedia> {
    val routes = mutableListOf<WidgetMedia>()

    fun visit(
        item: WidgetMedia,
        headers: Map<String, String>,
        group: String,
        depth: Int,
        parent: WidgetMedia? = null,
    ) {
        if (depth > 16 || routes.size >= 500) return
        val mergedHeaders = headers + item.headers
        val inherited =
            item.copy(
                headers = mergedHeaders,
                poster = item.poster.ifBlank { parent?.poster.orEmpty() },
                backdrop = item.backdrop.ifBlank { parent?.backdrop.orEmpty() },
                season = item.season ?: parent?.season,
                episode = item.episode ?: parent?.episode,
                resourceMetadata = JsonObject(parent?.resourceMetadata.orEmpty() + item.resourceMetadata),
            )
        if (item.videoUrl.isNotBlank() || (item.children.isEmpty() && item.requiresWidgetDetailLookup())) {
            routes +=
                inherited.copy(
                    title = listOf(group, item.title).filter(String::isNotBlank).joinToString(" · "),
                )
        }
        val childGroup =
            if (depth ==
                0
            ) {
                ""
            } else {
                listOf(group, item.title).filter(String::isNotBlank).joinToString(" · ")
            }
        item.children.forEach { visit(it, mergedHeaders, childGroup, depth + 1, inherited) }
    }
    visit(this, emptyMap(), "", 0)
    return routes
}
