package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.YfChip
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.pressable
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
            Column(
                Modifier.fillMaxWidth().glass(AppShapes.card, palette.card2, palette.border).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("显示内容", style = AppTypography.caption.strong, color = palette.sub2)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    motionItems(CalendarContentFilter.entries) { content ->
                        YfChip(
                            content.label,
                            state.contentFilter == content,
                            onClick = { component.store.accept(CalendarIntent.SelectContent(content)) },
                            role = Role.RadioButton,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
                Text(
                    "播出平台",
                    style = AppTypography.caption.strong,
                    color = palette.sub2,
                    modifier = Modifier.padding(top = 5.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    motionItem {
                        YfChip(
                            "全部平台",
                            state.platform == null,
                            onClick = { component.store.accept(CalendarIntent.SelectPlatform(null)) },
                            role = Role.RadioButton,
                        )
                    }
                    motionItems(state.availablePlatforms) { platform ->
                        YfChip(
                            platform,
                            state.platform == platform,
                            onClick = { component.store.accept(CalendarIntent.SelectPlatform(platform)) },
                            role = Role.RadioButton,
                        )
                    }
                }
                Text("筛选同时应用于日历", style = AppTypography.caption.regular, color = palette.sub2)
            }
        }
        motionItem {
            Text(
                "工具与同步",
                style = AppTypography.caption.strong,
                color = palette.sub2,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
        motionItem {
            Column(Modifier.fillMaxWidth().glass(AppShapes.card, palette.card2, palette.border)) {
                CalendarSettingsAction(AppIcons.WatchCalendar, "导出日历", "保存 ICS，导入系统日历", onExportCalendar)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(palette.border),
                )
                CalendarSettingsAction(AppIcons.Info, "导出诊断", "排期版本、缓存与媒体库状态", onExportDiagnostics)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(palette.border),
                )
                CalendarSettingsAction(AppIcons.Refresh, "刷新全部状态", "重新读取排期与资源") {
                    component.store.accept(CalendarIntent.Refresh)
                }
            }
        }
        motionItem {
            Text(
                "提醒可在“追剧”管理；提前量在单剧播出日历调整。",
                style = AppTypography.caption.regular,
                color = palette.sub2,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun CalendarSettingsAction(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = LocalAccentColors.current.accent, modifier = Modifier.size(20.dp))
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
