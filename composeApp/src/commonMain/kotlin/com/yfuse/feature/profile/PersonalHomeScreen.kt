package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.backend.BackendAccess
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Section
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.SettingsDivider
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.designsystem.ThemeText as Text

/** Personal features share the existing settings hierarchy and row styling. */
@Composable
internal fun PersonalSettingsSection(
    personal: PersonalLibraryRepository,
    onOpenContent: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenFamily: () -> Unit,
    onOpenHandoff: () -> Unit,
    onOpenTrakt: () -> Unit,
    onOpenStremio: () -> Unit,
) {
    val state by personal.state.collectAsState()
    val policy by personal.policy.collectAsState()
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val statsSummary =
        remember(state.history, policy.profileId) {
            val activeHistory =
                state.history.filter {
                    it.collection == PersonalCollection.History && !it.deleted && it.profileId == policy.profileId
                }
            val totalTitles = activeHistory.size
            val completedTitles = activeHistory.count { it.completed }
            val totalMinutes =
                activeHistory.sumOf { entry ->
                    val watched = if (entry.completed && entry.durationMs > 0L) entry.durationMs else entry.positionMs
                    watched.coerceAtLeast(0L)
                } / 60_000L
            Triple(totalTitles, completedTitles, totalMinutes)
        }
    Section(title = "个人与设备") {
        SettingsCard {
            if (statsSummary.first > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(onClickLabel = "查看观影统计", onClick = onOpenStatistics)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hoursText =
                        if (statsSummary.third >= 60L) {
                            "${"%.1f".format(statsSummary.third / 60.0)}h"
                        } else {
                            "${statsSummary.third}m"
                        }
                    WatchStatMetric(value = "${statsSummary.first}", label = "观看部数", accent = accent, sub = palette.sub)
                    WatchStatMetric(value = "${statsSummary.second}", label = "已看完", accent = accent, sub = palette.sub)
                    WatchStatMetric(value = hoursText, label = "累计时长", accent = accent, sub = palette.sub)
                }
                SettingsDivider()
            }
            if (!policy.child) {
                SettingRow(
                    "Stremio 插件",
                    embedded = true,
                    onClick = onOpenStremio,
                    icon = AppIcons.SettingsPlugin,
                    iconTint = SettingTint.servers,
                )
                SettingsDivider()
            }
            SettingRow(
                "我的片单",
                embedded = true,
                onClick = onOpenContent,
                icon = AppIcons.SettingsCollections,
                iconTint = SettingTint.library,
            )
            SettingsDivider()
            SettingRow(
                "观影统计",
                embedded = true,
                onClick = onOpenStatistics,
                icon = AppIcons.SettingsStats,
                iconTint = SettingTint.general,
            )
            SettingsDivider()
            SettingRow(
                "家庭资料",
                "${state.activeProfile.name} · ${state.profiles.size} 份资料",
                embedded = true,
                onClick = onOpenFamily,
                icon = AppIcons.SettingsFamily,
                iconTint = SettingTint.account,
            )
            if (BackendAccess.Default.enabled) {
                SettingsDivider()
                SettingRow(
                    "设备接力",
                    "跨设备继续观看",
                    embedded = true,
                    onClick = onOpenHandoff,
                    icon = AppIcons.SettingsHandoff,
                    iconTint = SettingTint.playback,
                )
            }
            if (BackendAccess.Default.enabled) {
                SettingsDivider()
                SettingRow(
                    "Trakt",
                    "观影记录同步",
                    embedded = true,
                    onClick = onOpenTrakt,
                    icon = AppIcons.SettingsTrakt,
                    iconTint = SettingTint.sync,
                )
            }
        }
    }
}

@Composable
private fun WatchStatMetric(
    value: String,
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    sub: androidx.compose.ui.graphics.Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = AppTypography.section.strong, color = accent)
        Text(label, style = AppTypography.caption.medium, color = sub)
    }
}
