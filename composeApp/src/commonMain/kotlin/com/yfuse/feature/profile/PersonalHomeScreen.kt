package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.designsystem.ThemeText as Text

/** Daily tasks precede account and appearance settings. */
@Composable
internal fun PersonalHomeScreen(
    personal: PersonalLibraryRepository,
    downloadCount: Int,
    onOpenCenter: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenFamily: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenHandoff: () -> Unit,
    onOpenTrakt: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMedia: (PersonalMediaRef) -> Unit,
) {
    val state by personal.state.collectAsState()
    val palette = LocalPalette.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 18.dp, bottom = TabBarInset),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        motionItem {
            Text(
                "${state.activeProfile.name} · 我的",
                style = AppTypography.section.strong,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        motionItem {
            Section(title = "我的内容") {
                SettingsCard {
                    SettingRow(
                        "收藏与想看",
                        "${state.favorites.size} 部收藏 · ${state.watchLater.size} 部想看 ›",
                        embedded = true,
                        onClick = onOpenCenter,
                    )
                    SettingsDivider()
                    SettingRow("观看历史", "${state.history.size} 条 ›", embedded = true, onClick = onOpenHistory)
                    SettingsDivider()
                    SettingRow("下载与离线库", "$downloadCount 项 ›", embedded = true, onClick = onOpenDownloads)
                }
            }
        }
        if (state.history.isNotEmpty()) {
            motionItem {
                Text(
                    "最近观看",
                    style = AppTypography.body.strong,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            motionItems(state.history.take(5), key = { it.identity }) { entry ->
                SettingRow(
                    title = entry.media.title,
                    value = if (entry.completed) "已看完 ›" else "${entry.positionMs / 60_000} 分钟 ›",
                    onClick = { onOpenMedia(entry.media) },
                )
            }
        }
        motionItem {
            Section(title = "资料与设备") {
                SettingsCard {
                    SettingRow("家庭资料", "${state.profiles.size} 份资料 · 新建或切换 ›", embedded = true, onClick = onOpenFamily)
                    SettingsDivider()
                    SettingRow(
                        "同步状态与恢复",
                        if (state.pendingSync) "有待同步内容 ›" else "查看同步范围和状态 ›",
                        embedded = true,
                        onClick = onOpenSync,
                    )
                    SettingsDivider()
                    SettingRow("账号与设备", "登录、安全与加密备份 ›", embedded = true, onClick = onOpenAccount)
                    SettingsDivider()
                    SettingRow("设备接力", "在另一台设备继续 ›", embedded = true, onClick = onOpenHandoff)
                    SettingsDivider()
                    SettingRow("Trakt", "授权与观影记录同步 ›", embedded = true, onClick = onOpenTrakt)
                }
            }
        }
        motionItem {
            SettingRow("设置", "播放、字幕、外观与诊断 ›", onClick = onOpenSettings)
            Text(
                "每份家庭资料分别保存清单、观看历史和追剧。",
                style = AppTypography.caption.regular,
                color = palette.sub2,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}
