package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuProbeResult
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.DanmakuSource
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.data.activeOr
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.Section
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.SettingsDivider
import com.yfuse.core.designsystem.SwitchRow
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.pressableTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun DanmakuSettingsScreen(
    preferences: DanmakuPreferences,
    onBack: () -> Unit,
    onSources: () -> Unit,
    onBlockedWords: () -> Unit,
) {
    val sources by preferences.sources.collectAsState()
    val activeSourceId by preferences.activeSourceId.collectAsState()
    val enabled by preferences.enabled.collectAsState()
    val displayArea by preferences.displayArea.collectAsState()
    val fontSize by preferences.fontSize.collectAsState()
    val speed by preferences.speed.collectAsState()
    val opacity by preferences.opacity.collectAsState()
    val mergeDuplicates by preferences.mergeDuplicates.collectAsState()
    val blockedWords by preferences.blockedWords.collectAsState()

    val repository =
        remember {
            runCatching { GlobalContext.get().getOrNull<DanmakuRepository>() }.getOrNull()
        }
    val scope = rememberCoroutineScope()
    val probeResults = remember { mutableStateMapOf<String, DanmakuProbeResult>() }
    val probingIds = remember { mutableStateMapOf<String, Boolean>() }
    var probingAll by remember { mutableStateOf(false) }

    fun testSingleSource(source: DanmakuSource) {
        val repo = repository ?: return
        if (probingIds[source.id] == true) return
        scope.launch {
            probingIds[source.id] = true
            try {
                probeResults[source.id] = repo.probeLatency(source)
            } finally {
                probingIds.remove(source.id)
            }
        }
    }

    fun testAllSources() {
        val repo = repository ?: return
        if (probingAll || sources.isEmpty()) return
        scope.launch {
            probingAll = true
            sources.forEach { probingIds[it.id] = true }
            try {
                val results =
                    coroutineScope {
                        sources.map { source ->
                            async {
                                val result = repo.probeLatency(source)
                                probeResults[source.id] = result
                                probingIds.remove(source.id)
                                result
                            }
                        }.awaitAll()
                    }
                results.forEach { probeResults[it.sourceId] = it }
            } finally {
                sources.forEach { probingIds.remove(it.id) }
                probingAll = false
            }
        }
    }

    val activeSource = sources.activeOr(activeSourceId)
    val fastestReachable =
        sources
            .mapNotNull { source ->
                probeResults[source.id]
                    ?.takeIf { it.reachable && it.latencyMs != null }
                    ?.let { source to it.latencyMs!! }
            }.minByOrNull { it.second }

    SettingsPage(
        title = "弹幕",
        subtitle = "弹幕源测速、显示样式与内容过滤",
        onBack = onBack,
    ) {
        motionItem(key = "danmaku-preview") {
            DanmakuStylePreviewCard(
                enabled = enabled,
                displayArea = displayArea,
                fontSize = fontSize,
                opacity = opacity,
                speed = speed,
                mergeDuplicates = mergeDuplicates,
            )
        }

        motionItem(key = "danmaku-sources") {
            val palette = LocalPalette.current
            val accent = LocalAccentColors.current
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "弹幕来源与延迟",
                        style = AppTypography.section.strong,
                        color = palette.text,
                        modifier = Modifier.weight(1f),
                    )
                    if (sources.isNotEmpty() && repository != null) {
                        Row(
                            Modifier
                                .pressableTarget(
                                    AppShapes.pill,
                                    enabled = !probingAll,
                                    onClickLabel = "测试全部弹幕源延迟",
                                    onClick = ::testAllSources,
                                ).glass(
                                    shape = AppShapes.pill,
                                    fill = accent.container,
                                    border = accent.border.copy(alpha = 0.35f),
                                ).padding(horizontal = 11.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (probingAll) {
                                OrbProgress(Modifier.size(12.dp), color = accent.accent)
                            } else {
                                Icon(AppIcons.Refresh, null, tint = accent.accent, modifier = Modifier.size(12.dp))
                            }
                            Text(
                                if (probingAll) "测速中" else "一键测速",
                                style = AppTypography.caption.strong,
                                color = accent.accent,
                            )
                        }
                    }
                    Text(
                        if (sources.isEmpty()) "添加" else "管理",
                        style = AppTypography.caption.strong,
                        color = palette.sub,
                        modifier =
                            Modifier
                                .pressableTarget(
                                    AppShapes.pill,
                                    onClickLabel = "管理弹幕来源",
                                    onClick = onSources,
                                ).glass(AppShapes.pill, palette.card2, palette.border)
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                }

                SettingsCard {
                    if (sources.isEmpty()) {
                        SettingRow(
                            "添加弹幕来源",
                            "配置弹弹play 兼容接口或模板链接",
                            true,
                            onSources,
                            icon = AppIcons.Danmaku,
                            iconTint = SettingTint.danmaku,
                        )
                    } else {
                        sources.forEachIndexed { index, source ->
                            if (index > 0) SettingsDivider()
                            DanmakuSourceLatencyRow(
                                source = source,
                                active = source.id == activeSource?.id,
                                probing = probingIds[source.id] == true,
                                probe = probeResults[source.id],
                                onSelect = { preferences.selectSource(source.id) },
                                onProbe = { testSingleSource(source) },
                            )
                        }
                        if (fastestReachable != null &&
                            fastestReachable.first.id != activeSource?.id
                        ) {
                            SettingsDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Semantic.Success))
                                Text(
                                    "最快可用：${fastestReachable.first.name}（${fastestReachable.second}ms）",
                                    style = AppTypography.caption.medium,
                                    color = palette.sub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "切换",
                                    style = AppTypography.caption.strong,
                                    color = accent.accent,
                                    modifier =
                                        Modifier
                                            .pressableTarget(
                                                AppShapes.pill,
                                                onClickLabel = "切换到最快弹幕源",
                                                onClick = { preferences.selectSource(fastestReachable.first.id) },
                                            ).glass(AppShapes.pill, accent.container, accent.border.copy(alpha = 0.3f))
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        motionItem(key = "danmaku-appearance") {
            Section(title = "显示样式") {
                SettingsCard {
                    SwitchRow(
                        "播放时默认开启弹幕",
                        enabled,
                        true,
                        icon = AppIcons.Danmaku,
                        iconTint = SettingTint.danmaku,
                        onChange = preferences::setEnabled,
                    )
                    SettingsDivider()
                    DanmakuSegmentedOptionRow(
                        title = "显示区域",
                        options = DanmakuDisplayArea.entries,
                        selected = displayArea,
                        label = { it.label },
                        onSelect = preferences::setDisplayArea,
                    )
                    SettingsDivider()
                    DanmakuSegmentedOptionRow(
                        title = "字号大小",
                        options = DanmakuFontSize.entries,
                        selected = fontSize,
                        label = { it.label },
                        onSelect = preferences::setFontSize,
                    )
                    SettingsDivider()
                    DanmakuSegmentedOptionRow(
                        title = "滚动速度",
                        options = DanmakuSpeed.entries,
                        selected = speed,
                        label = { it.label },
                        onSelect = preferences::setSpeed,
                    )
                    SettingsDivider()
                    DanmakuSegmentedOptionRow(
                        title = "不透明度",
                        options = DanmakuOpacity.entries,
                        selected = opacity,
                        label = { it.label },
                        onSelect = preferences::setOpacity,
                    )
                }
            }
        }

        motionItem(key = "danmaku-filter") {
            Section(title = "内容过滤") {
                SettingsCard {
                    SwitchRow(
                        "合并重复弹幕",
                        mergeDuplicates,
                        true,
                        icon = AppIcons.Danmaku,
                        iconTint = SettingTint.danmaku,
                        onChange = preferences::setMergeDuplicates,
                    )
                    SettingsDivider()
                    SettingRow(
                        "屏蔽词管理",
                        if (blockedWords.isEmpty()) "未设置" else "已屏蔽 ${blockedWords.size} 个关键词",
                        true,
                        onBlockedWords,
                        icon = AppIcons.DanmakuOff,
                        iconTint = SettingTint.danmaku,
                    )
                }
            }
        }
    }
}

@Composable
private fun DanmakuStylePreviewCard(
    enabled: Boolean,
    displayArea: DanmakuDisplayArea,
    fontSize: DanmakuFontSize,
    opacity: DanmakuOpacity,
    speed: DanmakuSpeed,
    mergeDuplicates: Boolean,
) {
    val accent = LocalAccentColors.current.accent
    val sampleSize = (13f * fontSize.scale).sp
    val textAlpha = if (enabled) opacity.alpha else 0.25f

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(118.dp)
            .clip(AppShapes.card)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF0A0D14), Color(0xFF131B2A), Color(0xFF0D111A)),
                ),
            ).border(1.dp, Color.White.copy(alpha = 0.14f), AppShapes.card),
    ) {
        // Subtle shaded zone illustrating the active danmaku display area.
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(displayArea.fraction.coerceIn(0.25f, 1f))
                .background(accent.copy(alpha = 0.08f)),
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "前方高能预警！这帧构图绝了",
                style = AppTypography.body.medium.copy(fontSize = sampleSize),
                color = Color.White.copy(alpha = textAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp),
            )
            if (displayArea.fraction >= 0.5f) {
                Text(
                    text = if (mergeDuplicates) "名场面打卡 ×16" else "名场面打卡 名场面打卡",
                    style = AppTypography.body.medium.copy(fontSize = sampleSize),
                    color = Color(0xFF8BE9FD).copy(alpha = textAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 64.dp),
                )
            }
            if (displayArea.fraction >= 0.75f) {
                Text(
                    text = "4K 杜比视界看着真舒服",
                    style = AppTypography.body.medium.copy(fontSize = sampleSize),
                    color = Color(0xFFFFD166).copy(alpha = textAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            text =
                if (enabled) {
                    "${displayArea.label} · ${fontSize.label}字号 · ${speed.label}速 · ${opacity.label}"
                } else {
                    "默认关闭弹幕（播放页可随时开启）"
                },
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.62f),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun DanmakuSourceLatencyRow(
    source: DanmakuSource,
    active: Boolean,
    probing: Boolean,
    probe: DanmakuProbeResult?,
    onSelect: () -> Unit,
    onProbe: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val latencyColor =
        when {
            probe == null -> palette.sub
            !probe.reachable -> Semantic.Error
            (probe.latencyMs ?: Long.MAX_VALUE) < 200L -> Semantic.Success
            (probe.latencyMs ?: Long.MAX_VALUE) < 600L -> Semantic.Warning
            else -> Semantic.Error
        }
    val latencyText =
        when {
            probing -> "测速中"
            probe == null -> "测速"
            probe.reachable && probe.latencyMs != null -> "${probe.latencyMs}ms"
            else -> probe.error ?: "不可达"
        }

    Row(
        Modifier
            .fillMaxWidth()
            .pressable(haptic = HapticSignal.Select, onClickLabel = "选择弹幕源 ${source.name}", onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (active) accent.accent else palette.border),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    source.name,
                    style = AppTypography.body.strong,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (source.supportsSearch) "API" else "模板",
                    style = AppTypography.caption.medium,
                    color = if (active) accent.accent else palette.sub2,
                    modifier =
                        Modifier
                            .clip(AppShapes.pill)
                            .background(if (active) accent.container else palette.card2)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Text(
                source.url.removePrefix("https://").removePrefix("http://"),
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier
                .pressableTarget(
                    AppShapes.pill,
                    enabled = !probing,
                    onClickLabel = "测试 ${source.name} 延迟",
                    onClick = onProbe,
                ).clip(AppShapes.pill)
                .background(
                    if (probe != null && !probing) {
                        latencyColor.copy(alpha = 0.14f)
                    } else {
                        palette.card2
                    },
                ).border(
                    1.dp,
                    if (probe != null && !probing) latencyColor.copy(alpha = 0.32f) else palette.border,
                    AppShapes.pill,
                ).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (probing) {
                OrbProgress(Modifier.size(11.dp), color = accent.accent)
            } else if (probe != null) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(latencyColor))
            }
            Text(
                latencyText,
                style = AppTypography.caption.strong,
                color = if (probing) accent.accent else latencyColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun <T> DanmakuSegmentedOptionRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = AppTypography.body.medium, color = palette.text)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    Modifier
                        .weight(1f)
                        .clip(AppShapes.chip)
                        .background(if (isSelected) accent.container else palette.card2)
                        .border(
                            1.dp,
                            if (isSelected) accent.border.copy(alpha = 0.45f) else palette.border,
                            AppShapes.chip,
                        ).pressable(
                            haptic = HapticSignal.Select,
                            onClickLabel = "$title ${label(option)}",
                            onClick = { onSelect(option) },
                        ).padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = AppTypography.caption.strong,
                        color = if (isSelected) accent.accent else palette.sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
