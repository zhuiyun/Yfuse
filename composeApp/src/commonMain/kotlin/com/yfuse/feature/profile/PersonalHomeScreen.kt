package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    onOpenFamily: () -> Unit,
    onOpenHandoff: () -> Unit,
    onOpenTrakt: () -> Unit,
) {
    val state by personal.state.collectAsState()
    Section(title = "个人与设备") {
        SettingsCard {
            SettingRow(
                "我的内容",
                "收藏 · 想看 · 观看历史",
                embedded = true,
                onClick = onOpenContent,
                icon = AppIcons.Bookmark,
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
            SettingsDivider()
            SettingRow(
                "设备接力",
                "在另一台设备继续观看",
                embedded = true,
                onClick = onOpenHandoff,
                icon = AppIcons.Play,
                iconTint = SettingTint.playback,
            )
            SettingsDivider()
            SettingRow(
                "Trakt",
                "授权与观影记录同步",
                embedded = true,
                onClick = onOpenTrakt,
                icon = AppIcons.Refresh,
                iconTint = SettingTint.account,
            )
        }
    }
}
