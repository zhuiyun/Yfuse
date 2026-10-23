package com.yfuse.feature.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.SettingSegmentControl
import com.yfuse.core.designsystem.contentHandoff
import com.yfuse.core.designsystem.cssShadow
import com.yfuse.core.designsystem.directionalHandoff
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.returnLocator
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.personal.LocalViewingSession
import com.yfuse.core.personal.LocalViewingStore
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.personal.ViewingSummary
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
    val accent = LocalAccentColors.current
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
    SettingsPage("观影统计", "当前资料 · 本机观看记录", onBack, modifier = Modifier.contentHandoff(historyOpen)) {
        item(key = "viewing-controls") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                SettingSegmentControl(
                    options = ViewingRange.entries.map { it.label },
                    selectedIndex = range.ordinal,
                    expanded = true,
                    onSelect = {
                        range = ViewingRange.entries[it]
                        selectedDay = null
                        historyLimit = 30
                    },
                )
            }
        }
        item(key = "viewing-summary") {
            ViewingSummaryCard(
                summary = summary,
                period =
                    when (range) {
                        ViewingRange.Month -> "${month.year} 年 ${month.monthValue} 月"
                        ViewingRange.Year -> "${month.year} 年"
                        ViewingRange.All -> "全部时间"
                    },
                empty = sessions.isEmpty(),
            )
        }
        item(key = "viewing-section-controls") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (historyOpen) "观看历史" else "观影日历",
                        style = AppTypography.body.strong,
                        color = palette.text,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        historyOpen = !historyOpen
                        selectedDay = null
                    }) {
                        Icon(
                            if (historyOpen) AppIcons.WatchCalendar else AppIcons.EpisodeList,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = accent.accent,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (historyOpen) "观影日历" else "观看历史",
                            style = AppTypography.body.medium,
                            color = accent.accent,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${month.year}年${month.monthValue}月",
                        style = AppTypography.body.medium,
                        modifier = Modifier.weight(1f),
                        color = palette.text,
                    )
                    IconButton(onClick = {
                        monthText = month.minusMonths(1).toString()
                        selectedDay = null
                    }) {
                        Icon(AppIcons.ChevronLeft, "上个月", tint = palette.sub, modifier = Modifier.size(20.dp))
                    }
                    TextButton(onClick = {
                        monthText = YearMonth.from(today).toString()
                        selectedDay = null
                    }) {
                        Text("今天", style = AppTypography.body.medium, color = accent.accent)
                    }
                    IconButton(onClick = {
                        monthText = month.plusMonths(1).toString()
                        selectedDay = null
                    }) {
                        Icon(AppIcons.ChevronRight, "下个月", tint = palette.sub, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (!historyOpen) {
            item(key = "viewing-calendar") {
                ViewingCalendar(
                    month = month,
                    today = today,
                    byDay = byDay,
                    selectedDay = selectedDay,
                    poster = ::poster,
                    onSelectDay = {
                        selectedDay = it
                        historyLimit = 30
                    },
                )
            }
            item(key = "viewing-trend") {
                ViewingMonthReview(month, sessions)
            }
            if (selectedDay != null || history.isNotEmpty()) {
                item(key = "viewing-history-heading") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selectedDay?.let { "$it · 观看历史" } ?: "观看历史",
                            style = AppTypography.body.strong,
                            color = palette.text,
                            modifier = Modifier.weight(1f),
                        )
                        if (selectedDay != null) {
                            TextButton(onClick = { selectedDay = null }) { Text("全部日期", color = accent.accent) }
                        }
                    }
                }
            }
        }
        if (history.isEmpty() && (historyOpen || selectedDay != null)) {
            item {
                Text(
                    "暂无观看记录",
                    style = AppTypography.body.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        items(history.take(historyLimit), key = { "viewing-history:${it.id}" }) { session ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .glass(
                        AppShapes.card,
                        palette.card2,
                        Color.White.copy(alpha = if (palette.isDark) 0.18f else 0.65f),
                    ).pressable { onOpenMedia(session.media) }
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
                }, modifier = Modifier.fillMaxWidth()) { Text("加载更多记录", color = accent.accent) }
            }
        }
    }
}

@Composable
private fun ViewingSummaryCard(
    summary: ViewingSummary,
    period: String,
    empty: Boolean,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .cssShadow(
                offsetY = 5.dp,
                blur = 20.dp,
                color = accent.accent.copy(alpha = 0.07f),
                shape = AppShapes.sheet,
            ).glass(AppShapes.sheet, palette.card2, Color.White.copy(alpha = if (palette.isDark) 0.18f else 0.75f))
            .background(Brush.verticalGradient(listOf(accent.accent.copy(alpha = 0.055f), Color.Transparent)))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$period · 观看时长", style = AppTypography.caption.medium, color = palette.sub)
            Text(viewingDuration(summary.watchedMs), style = AppTypography.display.strong, color = accent.accent)
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.sub2.copy(alpha = 0.12f)))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val metrics =
                listOf(
                    "观看天数" to "${summary.days} 天",
                    "电影" to "${summary.movies} 部",
                    "剧集" to "${summary.series} 部 · ${summary.episodes} 集",
                )
            val stacked = maxWidth / LocalDensity.current.fontScale < 270.dp
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    metrics.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                style = AppTypography.caption.regular,
                                color = palette.sub,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                value,
                                style = AppTypography.body.strong,
                                color = palette.text,
                                modifier = Modifier.weight(1.4f),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    metrics.forEachIndexed { index, (label, value) ->
                        Column(
                            Modifier.weight(if (index == 2) 1.35f else 1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(value, style = AppTypography.body.strong, color = palette.text)
                            Text(label, style = AppTypography.caption.regular, color = palette.sub)
                        }
                    }
                }
            }
        }
        if (empty) {
            Text(
                "播放后自动记录实际观看时间，旧的续播进度不会计入。",
                style = AppTypography.caption.reading,
                color = palette.sub2,
            )
        }
    }
}

@Composable
private fun ViewingCalendar(
    month: YearMonth,
    today: LocalDate,
    byDay: Map<String, List<LocalViewingSession>>,
    selectedDay: String?,
    poster: (LocalViewingSession) -> String?,
    onSelectDay: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val fontScale = LocalDensity.current.fontScale
    val selectionRim = lerp(Color(0xFFE5F3FF), accent.accent, 0.06f)
    BoxWithConstraints(Modifier.padding(horizontal = 20.dp).widthIn(max = 720.dp).fillMaxWidth()) {
        val compact = maxWidth < 600.dp || fontScale > 1.3f
        val cellHeight = if (compact) (40f * fontScale).coerceAtLeast(48f).dp else 80.dp
        Column(
            Modifier.fillMaxWidth().directionalHandoff(month.year * 12L + month.monthValue),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (compact) {
                Text(
                    "日期下方数字为当天观看作品数",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                (
                    if (compact) {
                        listOf(
                            "一",
                            "二",
                            "三",
                            "四",
                            "五",
                            "六",
                            "日",
                        )
                    } else {
                        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                    }
                ).forEach { weekday ->
                    Box(Modifier.weight(1f).padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                        Text(weekday, style = AppTypography.caption.regular, color = palette.sub2)
                    }
                }
            }
            viewingMonthCells(month).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { date ->
                        if (date == null) {
                            Spacer(Modifier.weight(1f).height(cellHeight))
                        } else {
                            val watched = byDay[date.toString()].orEmpty()
                            val unique = watched.distinctBy { it.seriesKey ?: it.media.identity }
                            val isSelected = selectedDay == date.toString()
                            val isToday = date == today
                            val shape = AppShapes.thumb
                            val dateSurface =
                                when {
                                    isSelected -> Modifier.glass(shape, accent.accent.copy(alpha = 0.12f), selectionRim)
                                    isToday || watched.isNotEmpty() ->
                                        Modifier.background(
                                            accent.accent.copy(alpha = 0.055f),
                                            shape,
                                        )
                                    else -> Modifier
                                }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(cellHeight)
                                    .then(dateSurface)
                                    .clip(shape)
                                    .returnLocator(isSelected)
                                    .pressable {
                                        onSelectDay(date.toString())
                                    }.semantics {
                                        contentDescription =
                                            "$date，${unique.size} 部作品，${watched.size} 次观看" + if (isToday) "，今天" else ""
                                        selected = isSelected
                                    }.padding(3.dp),
                                contentAlignment = if (compact) Alignment.Center else Alignment.TopStart,
                            ) {
                                if (compact) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            date.dayOfMonth.toString(),
                                            style =
                                                if (isSelected ||
                                                    isToday
                                                ) {
                                                    AppTypography.body.strong
                                                } else {
                                                    AppTypography.body.regular
                                                },
                                            color = if (isSelected || isToday) accent.accent else palette.text,
                                        )
                                        if (watched.isNotEmpty()) {
                                            Text(
                                                unique.size.toString(),
                                                style = AppTypography.caption.regular,
                                                color = accent.accent,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        } else if (isToday) {
                                            Box(Modifier.size(3.dp).background(accent.accent, AppShapes.pill))
                                        }
                                    }
                                } else {
                                    Text(
                                        date.dayOfMonth.toString(),
                                        style =
                                            if (isSelected ||
                                                isToday
                                            ) {
                                                AppTypography.caption.strong
                                            } else {
                                                AppTypography.caption.regular
                                            },
                                        color = if (isSelected || isToday) accent.accent else palette.text,
                                    )
                                    unique.firstOrNull()?.let { session ->
                                        FallbackImage(
                                            listOf(poster(session)),
                                            session.displayTitle,
                                            Modifier.fillMaxSize().padding(top = 19.dp).clip(AppShapes.micro),
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
    }
}

@Composable
private fun ViewingMonthReview(
    month: YearMonth,
    sessions: List<LocalViewingSession>,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .glass(AppShapes.card, palette.card2, Color.White.copy(alpha = if (palette.isDark) 0.18f else 0.65f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("${month.monthValue} 月观影回顾", style = AppTypography.body.strong, color = palette.text)
        if (summary.days > 0) {
            Text(
                "观看 ${viewingDuration(
                    summary.watchedMs,
                )} · ${summary.days} 天 · ${summary.movies} 部电影 · ${summary.series} 部剧集",
                style = AppTypography.caption.reading,
                color = palette.sub,
            )
            Row(
                Modifier.fillMaxWidth().height(64.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                totals.forEachIndexed { index, duration ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height((if (duration > 0L) maxOf(3f, 60f * duration / maximum) else 2f).dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                if (duration >
                                    0L
                                ) {
                                    accent.accent.copy(alpha = 0.75f)
                                } else {
                                    palette.sub2.copy(alpha = 0.12f)
                                },
                            ).semantics {
                                contentDescription = "${month.atDay(
                                    index + 1,
                                )}，${viewingDuration(duration)}"
                            },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1日", style = AppTypography.caption.regular, color = palette.sub2)
                Text("每日观看时长", style = AppTypography.caption.regular, color = palette.sub2)
                Text("${month.lengthOfMonth()}日", style = AppTypography.caption.regular, color = palette.sub2)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.sub2.copy(alpha = 0.12f)))
            Text(
                "观影日平均 ${viewingDuration(summary.watchedMs / summary.days)}",
                style = AppTypography.caption.regular,
                color = palette.sub,
            )
            val busiest = totals.indices.maxByOrNull { totals[it] } ?: 0
            Text(
                "最投入的一天：${month.monthValue}月${busiest + 1}日，${viewingDuration(totals[busiest])}",
                style = AppTypography.caption.reading,
                color = palette.sub,
            )
            favorite?.firstOrNull()?.let {
                Text("看得最多：${it.displayTitle}", style = AppTypography.caption.reading, color = palette.sub)
            }
        } else {
            Text("这个月还没有观影记录，观看后的每日趋势会显示在这里。", style = AppTypography.caption.reading, color = palette.sub2)
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
