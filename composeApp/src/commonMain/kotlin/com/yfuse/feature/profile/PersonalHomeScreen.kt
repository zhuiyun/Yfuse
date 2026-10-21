package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.yfuse.backend.BackendAccess
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Section
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.SettingsDivider
import com.yfuse.core.personal.PersonalLibraryRepository

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
    Section(title = "个人与设备") {
        SettingsCard {
            if (!policy.child) {
                SettingRow(
                    "Stremio 插件",
                    "插件管理 · 目录 · 搜索 · 播放",
                    embedded = true,
                    onClick = onOpenStremio,
                    icon = AppIcons.Grid,
                    iconTint = SettingTint.servers,
                )
                SettingsDivider()
            }
            SettingRow(
                "我的片单",
                "想看 · 收藏 · 观看历史",
                embedded = true,
                onClick = onOpenContent,
                icon = AppIcons.Bookmark,
                iconTint = SettingTint.account,
            )
            SettingsDivider()
            SettingRow(
                "观影统计",
                "时长 · 日历 · 趋势 · 观影回顾",
                embedded = true,
                onClick = onOpenStatistics,
                icon = AppIcons.WatchCalendar,
                iconTint = SettingTint.account,
            )
            SettingsDivider()
            SettingRow(
                "家庭资料",
                "${state.activeProfile.name} · ${state.profiles.size} 份资料",
                embedded = true,
                onClick = onOpenFamily,
                icon = AppIcons.User,
                iconTint = SettingTint.account,
            )
            if (BackendAccess.Default.enabled) {
                SettingsDivider()
                SettingRow(
                    "设备接力",
                    "在另一台设备继续观看",
                    embedded = true,
                    onClick = onOpenHandoff,
                    icon = AppIcons.Play,
                    iconTint = SettingTint.playback,
                )
            }
            if (BackendAccess.Default.enabled) {
                SettingsDivider()
                SettingRow(
                    "Trakt",
                    "播放记录、收藏与待看同步",
                    embedded = true,
                    onClick = onOpenTrakt,
                    icon = AppIcons.Refresh,
                    iconTint = SettingTint.account,
                )
            }
        }
    }
}
