package com.yfuse.feature.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.personal.LocalViewingSession
import com.yfuse.core.personal.LocalViewingStore
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.personal.viewingMonthCells
import com.yfuse.core.personal.viewingSummary
import com.yfuse.core.personal.watchedInRange
import com.yfuse.feature.profile.SettingsPage
import org.koin.core.context.GlobalContext
import java.time.LocalDate
import java.time.YearMonth
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

private enum class ViewingRange(
    val label: String,
) {
    Month("当月"),
    Year("当年"),
    All("全部"),
}

@Composable
fun ViewingStatisticsScreen(
    store: LocalViewingStore,
    onBack: () -> Unit,
    onOpenMedia: (PersonalMediaRef) -> Unit,
) {
    val sessions by store.sessions.collectAsState()
    val registry = remember { GlobalContext.get().getOrNull<ServerRegistry>() }
    val servers =
        registry
            ?.data
            ?.collectAsState()
            ?.value
            ?.servers
            .orEmpty()
    val palette = LocalPalette.current
    val today = LocalDate.now()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val month = YearMonth.parse(monthText)
    var range by rememberSaveable { mutableStateOf(ViewingRange.Month) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var historyLimit by rememberSaveable { mutableStateOf(30) }
    val from =
        when (range) {
            ViewingRange.Month -> month.atDay(1)
            ViewingRange.Year -> LocalDate.of(month.year, 1, 1)
            ViewingRange.All -> null
        }
    val through =
        when (range) {
            ViewingRange.Month -> month.atEndOfMonth()
            ViewingRange.Year -> LocalDate.of(month.year, 12, 31)
            ViewingRange.All -> null
        }
    val summary = remember(sessions, from, through) { viewingSummary(sessions, from, through) }
    val byDay =
        remember(sessions) {
            sessions
                .flatMap { session -> session.watchedByDay.keys.map { it to session } }
                .groupBy({ it.first }, { it.second })
        }
    val history =
        remember(sessions, from, through, selectedDay, historyOpen) {
            sessions.filter {
                if (selectedDay !=
                    null
                ) {
                    (it.watchedByDay[selectedDay] ?: 0L) > 0L
                } else {
                    it.watchedInRange(from, through) > 0L
                }
            }
        }

    fun poster(session: LocalViewingSession): String? {
        val server = servers.firstOrNull { it.id == session.media.serverId } ?: return null
        return EmbyImages.primary(
            server.baseUrl,
            session.posterItemId ?: session.media.serverItemId.orEmpty(),
            session.posterTag,
            maxHeight = 200,
            accessToken = server.accessToken,
        )
    }
    SettingsPage("观影统计", "当前资料 · 仅记录本机实际观看", onBack) {
        item(key = "viewing-controls") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Row {
                    ViewingRange.entries.forEach { option ->
                        TextButton(onClick = {
                            range = option
                            selectedDay = null
                            historyLimit = 30
                        }) {
                            Text(option.label, color = if (range == option) palette.success else palette.sub)
                        }
                    }
                }
                TextButton(onClick = {
                    historyOpen = !historyOpen
                    selectedDay = null
                }) {
                    Text(if (historyOpen) "观影日历" else "观看历史", color = palette.success)
                }
            }
        }
        item(key = "viewing-summary") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ViewingMetric("观看时长", viewingDuration(summary.watchedMs), Modifier.weight(1f))
                    ViewingMetric("观看天数", "${summary.days} 天", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ViewingMetric("电影", "${summary.movies} 部", Modifier.weight(1f))
                    ViewingMetric("剧集", "${summary.series} 部 · ${summary.episodes} 集", Modifier.weight(1f))
                }
                if (sessions.isEmpty()) {
                    Text("开始播放后，这里会自动记录观影时间。旧的续播进度不会计入时长。", style = AppTypography.caption.regular, color = palette.sub)
                }
            }
        }
        if (!historyOpen) {
            item(key = "viewing-calendar") {
                Column(Modifier.padding(horizontal = 20.dp).widthIn(max = 720.dp).fillMaxWidth()) {
                    Text("观影日历", style = AppTypography.body.strong, color = palette.text)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            monthText = month.minusMonths(1).toString()
                            selectedDay = null
                        }) {
                            Icon(AppIcons.ChevronLeft, "上个月", tint = palette.text)
                        }
                        Text(
                            "${month.year}年${month.monthValue}月",
                            style = AppTypography.body.strong,
                            modifier = Modifier.weight(1f),
                            color = palette.text,
                        )
                        TextButton(onClick = {
                            monthText = YearMonth.from(today).toString()
                            selectedDay = null
                        }) {
                            Text("今天", color = palette.success)
                        }
                        IconButton(onClick = {
                            monthText = month.plusMonths(1).toString()
                            selectedDay = null
                        }) {
                            Icon(AppIcons.ChevronRight, "下个月", tint = palette.text)
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEach {
                            Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(it, style = AppTypography.caption.regular, color = palette.sub)
                            }
                        }
                    }
                    viewingMonthCells(month).chunked(7).forEach { week ->
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            week.forEach { date ->
                                if (date == null) {
                                    Spacer(Modifier.weight(1f).height(92.dp))
                                } else {
                                    val watched = byDay[date.toString()].orEmpty()
                                    val unique = watched.distinctBy { it.seriesKey ?: it.media.identity }
                                    val shape = RoundedCornerShape(10.dp)
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(92.dp)
                                            .clip(shape)
                                            .background(if (watched.isEmpty()) palette.card2 else palette.card)
                                            .border(
                                                if (selectedDay ==
                                                    date.toString()
                                                ) {
                                                    2.dp
                                                } else {
                                                    0.dp
                                                },
                                                palette.success,
                                                shape,
                                            ).pressable {
                                                selectedDay = date.toString()
                                                historyLimit = 30
                                            }.semantics { contentDescription = "$date，${watched.size} 次观看" }
                                            .padding(5.dp),
                                    ) {
                                        Text(
                                            date.dayOfMonth.toString(),
                                            style = AppTypography.caption.regular,
                                            color =
                                                if (date ==
                                                    today
                                                ) {
                                                    palette.success
                                                } else {
                                                    palette.text
                                                },
                                        )
                                        unique.firstOrNull()?.let { session ->
                                            FallbackImage(
                                                listOf(poster(session)),
                                                session.displayTitle,
                                                Modifier
                                                    .fillMaxSize()
                                                    .padding(
                                                        top = 19.dp,
                                                    ).clip(RoundedCornerShape(5.dp)),
                                                contentScale = ContentScale.Fit,
                                                progressive = false,
                                            )
                                            if (unique.size > 1) {
                                                Text(
                                                    "+${unique.size - 1}",
                                                    style = AppTypography.caption.regular,
                                                    color = palette.text,
                                                    modifier =
                                                        Modifier
                                                            .align(
                                                                Alignment.BottomEnd,
                                                            ).background(palette.card)
                                                            .padding(2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "viewing-trend") {
                ViewingMonthReview(month, sessions)
            }
        }
        item(key = "viewing-history-heading") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selectedDay?.let {
                        "$it · 观看历史"
                    } ?: "观看历史",
                    style = AppTypography.body.strong,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
                if (selectedDay !=
                    null
                ) {
                    TextButton(onClick = { selectedDay = null }) { Text("全部日期", color = palette.success) }
                }
            }
        }
        if (history.isEmpty()) {
            item { Text("暂无观看记录", color = palette.sub, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        items(history.take(historyLimit), key = { "viewing-history:${it.id}" }) { session ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.card)
                    .pressable { onOpenMedia(session.media) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FallbackImage(
                    listOf(poster(session)),
                    session.displayTitle,
                    Modifier.width(44.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)),
                    progressive = false,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        session.displayTitle,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val coordinate = session.episodeNumber?.let { "S${session.seasonNumber ?: 1} · E$it · " }.orEmpty()
                    Text(
                        coordinate +
                            viewingDuration(
                                session.watchedInRange(
                                    selectedDay?.let(LocalDate::parse) ?: from,
                                    selectedDay?.let(LocalDate::parse) ?: through,
                                ),
                            ),
                        style = AppTypography.caption.regular,
                        color = palette.sub,
                    )
                    val watchedDate =
                        selectedDay ?: session.watchedByDay.keys
                            .filter {
                                (from == null || it >= from.toString()) && (through == null || it <= through.toString())
                            }.maxOrNull()
                            .orEmpty()
                    Text(
                        watchedDate + if (session.completed) " · 已看完" else "",
                        style = AppTypography.caption.regular,
                        color = palette.sub,
                    )
                }
                Icon(AppIcons.ChevronRight, "查看详情", tint = palette.sub, modifier = Modifier.size(18.dp))
            }
        }
        if (history.size > historyLimit) {
            item {
                TextButton(onClick = {
                    historyLimit += 30
                }, modifier = Modifier.fillMaxWidth()) { Text("加载更多记录", color = palette.success) }
            }
        }
    }
}

@Composable
private fun ViewingMetric(
    label: String,
    value: String,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(palette.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = AppTypography.caption.regular, color = palette.sub)
        Text(value, style = AppTypography.body.strong, color = palette.text)
    }
}

@Composable
private fun ViewingMonthReview(
    month: YearMonth,
    sessions: List<LocalViewingSession>,
) {
    val palette = LocalPalette.current
    val from = month.atDay(1)
    val through = month.atEndOfMonth()
    val totals =
        remember(month, sessions) {
            (1..month.lengthOfMonth()).map { day ->
                sessions.sumOf { it.watchedByDay[month.atDay(day).toString()] ?: 0L }
            }
        }
    val maximum = totals.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val summary = viewingSummary(sessions, from, through)
    val favorite =
        sessions
            .filter { it.watchedInRange(from, through) > 0L }
            .groupBy { it.seriesKey ?: it.media.identity }
            .values
            .maxByOrNull { group ->
                group.sumOf { it.watchedInRange(from, through) }
            }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("每日趋势", style = AppTypography.body.strong, color = palette.text)
        Row(
            Modifier.fillMaxWidth().height(84.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            totals.forEachIndexed { index, duration ->
                Box(
                    Modifier
                        .weight(1f)
                        .height((if (duration > 0L) maxOf(3f, 80f * duration / maximum) else 2f).dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (duration > 0L) palette.success else palette.card2)
                        .semantics { contentDescription = "${month.atDay(index + 1)}，${viewingDuration(duration)}" },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1日", style = AppTypography.caption.regular, color = palette.sub)
            Text("${month.lengthOfMonth()}日", style = AppTypography.caption.regular, color = palette.sub)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.card)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${month.year}年${month.monthValue}月观影回顾", style = AppTypography.body.strong, color = palette.text)
            val totalDuration = viewingDuration(summary.watchedMs)
            Text(
                "观看 $totalDuration · ${summary.days} 天 · ${summary.movies} 部电影 · ${summary.series} 部剧集",
                style = AppTypography.caption.regular,
                color = palette.sub,
            )
            if (summary.days > 0) {
                Text(
                    "观影日平均 ${viewingDuration(summary.watchedMs / summary.days)}",
                    style = AppTypography.caption.regular,
                    color = palette.sub,
                )
                val busiest = totals.indices.maxByOrNull { totals[it] } ?: 0
                Text(
                    "最投入的一天：${month.monthValue}月${busiest + 1}日，${viewingDuration(totals[busiest])}",
                    style = AppTypography.caption.regular,
                    color = palette.sub,
                )
                favorite?.firstOrNull()?.let {
                    Text("看得最多：${it.displayTitle}", style = AppTypography.caption.regular, color = palette.sub)
                }
            } else {
                Text("这个月还没有观影记录", style = AppTypography.caption.regular, color = palette.sub)
            }
        }
    }
}

internal fun viewingDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1_000L
    val minutes = seconds / 60L
    return when {
        seconds == 0L -> "0 分钟"
        minutes == 0L -> "$seconds 秒"
        minutes < 60L -> "$minutes 分钟"
        else -> "${minutes / 60L} 小时 ${minutes % 60L} 分钟"
    }
}
