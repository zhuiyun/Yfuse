package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.selectionColor
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.core.designsystem.flatGlass as glass

@Composable
internal fun CalendarSettingsPane(
    state: CalendarState,
    component: CalendarComponent,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onExportCalendar: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
                bottom = bottomContentInset,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        motionItem {
            Text("内容类型", style = AppTypography.body.strong, color = palette.text)
            Spacer(Modifier.height(7.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                motionItems(CalendarContentFilter.entries) { content ->
                    val active = state.contentFilter == content
                    Text(
                        content.label,
                        style = AppTypography.caption.strong,
                        color = selectionColor(if (active) accent.onAccent else palette.body),
                        modifier =
                            Modifier
                                .pressable {
                                    component.store.accept(CalendarIntent.SelectContent(content))
                                }.touchTarget()
                                .clip(AppShapes.chip)
                                .background(selectionColor(if (active) accent.accent else Color.Transparent))
                                .then(
                                    if (active) {
                                        Modifier
                                    } else {
                                        Modifier.glass(AppShapes.chip, palette.card2, palette.border)
                                    },
                                ).padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }
        motionItem {
            Text("播出平台", style = AppTypography.body.strong, color = palette.text)
            Spacer(Modifier.height(7.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                motionItem {
                    val active = state.platform == null
                    Text(
                        "全部平台",
                        style = AppTypography.caption.strong,
                        color = selectionColor(if (active) accent.onAccent else palette.body),
                        modifier =
                            Modifier
                                .pressable {
                                    component.store.accept(CalendarIntent.SelectPlatform(null))
                                }.touchTarget()
                                .clip(AppShapes.chip)
                                .background(selectionColor(if (active) accent.accent else Color.Transparent))
                                .then(
                                    if (active) {
                                        Modifier
                                    } else {
                                        Modifier.glass(AppShapes.chip, palette.card2, palette.border)
                                    },
                                ).padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
                motionItems(state.availablePlatforms) { platform ->
                    val active = state.platform == platform
                    Text(
                        platform,
                        style = AppTypography.caption.strong,
                        color = selectionColor(if (active) accent.onAccent else palette.body),
                        modifier =
                            Modifier
                                .pressable {
                                    component.store.accept(CalendarIntent.SelectPlatform(platform))
                                }.touchTarget()
                                .clip(AppShapes.chip)
                                .background(selectionColor(if (active) accent.accent else Color.Transparent))
                                .then(
                                    if (active) {
                                        Modifier
                                    } else {
                                        Modifier.glass(AppShapes.chip, palette.card2, palette.border)
                                    },
                                ).padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }
        motionItem {
            CalendarSettingsAction(
                title = "导出 ICS 日历",
                description = "导入系统日历或其他日历应用",
                onClick = onExportCalendar,
            )
        }
        motionItem {
            CalendarSettingsAction(
                title = "导出诊断",
                description = "包含排期版本、缓存与媒体库状态",
                onClick = onExportDiagnostics,
            )
        }
        motionItem {
            CalendarSettingsAction(
                title = "立即刷新全部状态",
                description = "绕过排期和媒体库身份缓存",
                onClick = { component.store.accept(CalendarIntent.Refresh) },
            )
        }
        motionItem {
            Text(
                "平台和内容筛选会同时作用于“日历”页；提醒模式可在“追剧”页切换，提前量在剧集详情页的“播出日历”中调整。",
                style = AppTypography.caption.regular,
                color = palette.sub2,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CalendarSettingsAction(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .touchTarget()
            .glass(AppShapes.card, palette.card2, palette.border)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.body.strong, color = palette.text)
            Spacer(Modifier.height(3.dp))
            Text(description, style = AppTypography.caption.regular, color = palette.sub2)
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = palette.sub2,
            modifier = Modifier.size(14.dp),
        )
    }
}
