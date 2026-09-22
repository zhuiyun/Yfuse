package com.yfuse.tv.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.feature.profile.ProfileComponent

@Composable
internal fun TvDanmakuSettingsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:danmaku"
    val prefs = component.danmakuPreferences
    val enabled by prefs.enabled.collectAsState()
    val displayArea by prefs.displayArea.collectAsState()
    val fontSize by prefs.fontSize.collectAsState()
    val speed by prefs.speed.collectAsState()
    val opacity by prefs.opacity.collectAsState()
    val mergeDuplicates by prefs.mergeDuplicates.collectAsState()
    val blockedWords by prefs.blockedWords.collectAsState()
    var newWord by remember { mutableStateOf("") }

    TvSettingsPageScaffold(page = TvSettingsPage.Danmaku) {
        item(key = "danmaku-enabled") {
            TvToggleRow(
                title = "显示弹幕",
                checked = enabled,
                stableId = "danmaku:enabled",
                focusMemory = focusMemory,
                onToggle = prefs::setEnabled,
                icon = AppIcons.Danmaku,
                focusScope = focusScope,
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }

        item(key = "danmaku-section-display") { TvSettingsSectionTitle("显示") }
        item(key = "danmaku-area") {
            TvChoiceRow(
                title = "显示区域",
                options = DanmakuDisplayArea.entries,
                selected = displayArea,
                label = { it.label },
                stableId = "danmaku:area",
                focusMemory = focusMemory,
                onSelect = prefs::setDisplayArea,
                icon = AppIcons.AspectFit,
                focusScope = focusScope,
                enabled = enabled,
                subtitle = "弹幕最多占据画面顶部的这一部分",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "danmaku-font-size") {
            TvChoiceRow(
                title = "字号",
                options = DanmakuFontSize.entries,
                selected = fontSize,
                label = { it.label },
                stableId = "danmaku:font-size",
                focusMemory = focusMemory,
                onSelect = prefs::setFontSize,
                icon = AppIcons.Subtitle,
                focusScope = focusScope,
                enabled = enabled,
                subtitle = "电视观看距离远，建议比手机大一档",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "danmaku-speed") {
            TvChoiceRow(
                title = "滚动速度",
                options = DanmakuSpeed.entries,
                selected = speed,
                label = { it.label },
                stableId = "danmaku:speed",
                focusMemory = focusMemory,
                onSelect = prefs::setSpeed,
                icon = AppIcons.Forward,
                focusScope = focusScope,
                enabled = enabled,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "danmaku-opacity") {
            TvChoiceRow(
                title = "不透明度",
                options = DanmakuOpacity.entries,
                selected = opacity,
                label = { it.label },
                stableId = "danmaku:opacity",
                focusMemory = focusMemory,
                onSelect = prefs::setOpacity,
                icon = AppIcons.Eye,
                focusScope = focusScope,
                enabled = enabled,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "danmaku-merge") {
            TvToggleRow(
                title = "合并重复弹幕",
                checked = mergeDuplicates,
                stableId = "danmaku:merge",
                focusMemory = focusMemory,
                onToggle = prefs::setMergeDuplicates,
                icon = AppIcons.Collapse,
                focusScope = focusScope,
                enabled = enabled,
                subtitle = "相同内容只显示一条，并标注重复次数",
                navigationRequester = navigationRequester,
            )
        }

        item(key = "danmaku-section-filter") { TvSettingsSectionTitle("屏蔽词") }
        item(key = "danmaku-new-word") {
            TvSettingsTextField(
                value = newWord,
                label = "新增屏蔽词",
                stableId = "danmaku:new-word",
                focusScope = focusScope,
                focusMemory = focusMemory,
                onValueChange = { newWord = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        item(key = "danmaku-add-word") {
            TvSettingRow(
                title = "添加",
                value = "",
                stableId = "danmaku:add-word",
                focusMemory = focusMemory,
                onClick = {
                    prefs.addBlockedWord(newWord)
                    newWord = ""
                },
                icon = AppIcons.Add,
                focusScope = focusScope,
                enabled = newWord.isNotBlank(),
                navigationRequester = navigationRequester,
            )
        }
        if (blockedWords.isEmpty()) {
            item(key = "danmaku-no-words") {
                TvSettingsNote("还没有屏蔽词。含有屏蔽词的弹幕不会显示。")
            }
        }
        blockedWords.forEach { word ->
            item(key = "danmaku-word:$word") {
                TvSettingRow(
                    title = word,
                    value = "移除",
                    stableId = "danmaku:word:$word",
                    focusMemory = focusMemory,
                    onClick = { prefs.removeBlockedWord(word) },
                    icon = AppIcons.Close,
                    focusScope = focusScope,
                    navigationRequester = navigationRequester,
                )
            }
        }
    }
}

@Composable
internal fun TvWatchTogetherSettingsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:watch"
    val prefs = component.watchTogetherPreferences
    val nickname by prefs.nickname.collectAsState()
    val avatarId by prefs.avatarId.collectAsState()
    val chatPreview by prefs.chatPreviewEnabled.collectAsState()
    val chatDanmaku by prefs.chatDanmakuEnabled.collectAsState()
    val account by component.account.state.collectAsState()
    var draftNickname by remember(nickname) { mutableStateOf(nickname) }

    TvSettingsPageScaffold(page = TvSettingsPage.WatchTogether) {
        if (account !is com.yfuse.core.account.AccountState.SignedIn) {
            item(key = "watch-signed-out") {
                TvSettingsNote("一起看需要先登录 Yfuse 账号。房间与聊天都通过账号服务中转。", tone = TvWarning)
            }
        }

        item(key = "watch-section-profile") { TvSettingsSectionTitle("房间资料") }
        item(key = "watch-nickname") {
            TvSettingsTextField(
                value = draftNickname,
                label = "昵称",
                stableId = "watch:nickname",
                focusScope = focusScope,
                focusMemory = focusMemory,
                onValueChange = { draftNickname = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                focusRequester = firstRowRequester,
            )
        }
        item(key = "watch-save-nickname") {
            TvSettingRow(
                title = "保存昵称",
                value = "",
                stableId = "watch:save-nickname",
                focusMemory = focusMemory,
                onClick = { prefs.setProfile(draftNickname, avatarId) },
                icon = AppIcons.Check,
                focusScope = focusScope,
                enabled = draftNickname.isNotBlank() && draftNickname != nickname,
                subtitle = "房间里的其他人会看到这个名字",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "watch-avatar") {
            TvSettingRow(
                title = "头像",
                value = "第 ${avatarId + 1} 个",
                stableId = "watch:avatar",
                focusMemory = focusMemory,
                onClick = { prefs.setProfile(nickname, avatarId + 1) },
                icon = AppIcons.User,
                focusScope = focusScope,
                subtitle = "选择一个内置头像，到末尾后会回到第一个",
                navigationRequester = navigationRequester,
            )
        }

        item(key = "watch-section-chat") { TvSettingsSectionTitle("聊天显示") }
        item(key = "watch-chat-preview") {
            TvToggleRow(
                title = "聊天浮层",
                checked = chatPreview,
                stableId = "watch:chat-preview",
                focusMemory = focusMemory,
                onToggle = prefs::setChatPreviewEnabled,
                icon = AppIcons.Chat,
                focusScope = focusScope,
                subtitle = "在播放器角落显示最近几条消息",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "watch-chat-danmaku") {
            TvToggleRow(
                title = "聊天以弹幕显示",
                checked = chatDanmaku,
                stableId = "watch:chat-danmaku",
                focusMemory = focusMemory,
                onToggle = prefs::setChatDanmakuEnabled,
                icon = AppIcons.Danmaku,
                focusScope = focusScope,
                subtitle = "房间消息像弹幕一样从画面上飘过",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "watch-note") {
            TvSettingsNote("房间的创建与加入在播放器里完成，播放时按遥控器的菜单键打开一起看面板。")
        }
    }
}
