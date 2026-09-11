package com.yfuse.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.ServerLayout
import com.yfuse.core.model.StartupTab
import com.yfuse.feature.profile.ProfileComponent
import com.yfuse.feature.profile.releaseBackgroundImage
import com.yfuse.feature.profile.rememberBackgroundImagePicker

@Composable
internal fun TvAppearanceSettingsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:appearance"
    val prefs = component.themePreferences
    val mode by prefs.mode.collectAsState()
    val dialogAnimation by prefs.dialogAnimation.collectAsState()
    val glassStyle by prefs.glassStyle.collectAsState()
    val reduceTransparency by prefs.reduceTransparency.collectAsState()
    val largeText by prefs.largeText.collectAsState()
    val reduceMotion by prefs.reduceMotion.collectAsState()
    val serverLayout by prefs.serverLayout.collectAsState()
    val startupTab by prefs.startupTab.collectAsState()
    val backgroundImage by prefs.backgroundImage.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }

    val pickBackground =
        rememberBackgroundImagePicker { uri ->
            if (uri != null) {
                backgroundImage?.takeIf { it != uri }?.let(::releaseBackgroundImage)
                prefs.setBackgroundImage(uri)
                status = "背景图已更新"
            }
        }

    TvSettingsPageScaffold(page = TvSettingsPage.Appearance, status = status) {
        item(key = "appearance-section-theme") { TvSettingsSectionTitle("主题") }
        item(key = "appearance-mode") {
            TvChoiceRow(
                title = "界面模式",
                options = ThemeMode.entries,
                selected = mode,
                label = { it.label },
                stableId = "appearance:mode",
                focusMemory = focusMemory,
                onSelect = prefs::setMode,
                icon = AppIcons.Grid,
                focusScope = focusScope,
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "appearance-glass") {
            TvChoiceRow(
                title = "玻璃质感",
                options = GlassStyle.entries,
                selected = glassStyle,
                label = { it.label },
                stableId = "appearance:glass",
                focusMemory = focusMemory,
                onSelect = prefs::setGlassStyle,
                icon = AppIcons.Expand,
                focusScope = focusScope,
                subtitle = "面板与弹窗的背景处理方式",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "appearance-dialog-animation") {
            TvChoiceRow(
                title = "弹窗动画",
                options = DialogAnimation.entries,
                selected = dialogAnimation,
                label = { it.label },
                stableId = "appearance:dialog-animation",
                focusMemory = focusMemory,
                onSelect = prefs::setDialogAnimation,
                icon = AppIcons.Refresh,
                focusScope = focusScope,
                subtitle = dialogAnimation.description,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "appearance-server-layout") {
            TvChoiceRow(
                title = "服务器列表布局",
                options = ServerLayout.entries,
                selected = serverLayout,
                label = { it.label },
                stableId = "appearance:server-layout",
                focusMemory = focusMemory,
                onSelect = prefs::setServerLayout,
                icon = AppIcons.TabServers,
                focusScope = focusScope,
                navigationRequester = navigationRequester,
            )
        }

        item(key = "appearance-section-background") { TvSettingsSectionTitle("背景") }
        item(key = "appearance-background") {
            TvSettingRow(
                title = "背景图",
                value = if (backgroundImage != null) "已设置" else "未设置",
                stableId = "appearance:background",
                focusMemory = focusMemory,
                onClick = {
                    // Televisions frequently ship without a photo picker at all, which surfaces
                    // as a missing activity rather than a cancelled pick.
                    runCatching { pickBackground() }
                        .onFailure { status = "这台设备没有可用的图片选择器。" }
                },
                icon = AppIcons.Movie,
                focusScope = focusScope,
                subtitle = "整个应用的背景；正文仍然画在主题自己的底色上",
                navigationRequester = navigationRequester,
            )
        }
        if (backgroundImage != null) {
            item(key = "appearance-background-clear") {
                TvSettingRow(
                    title = "移除背景图",
                    value = "",
                    stableId = "appearance:background-clear",
                    focusMemory = focusMemory,
                    onClick = {
                        backgroundImage?.let(::releaseBackgroundImage)
                        prefs.setBackgroundImage(null)
                        status = "背景图已移除"
                    },
                    icon = AppIcons.Close,
                    focusScope = focusScope,
                    subtitle = "同时释放对这张图片的长期读取授权",
                    navigationRequester = navigationRequester,
                )
            }
        }

        item(key = "appearance-section-startup") { TvSettingsSectionTitle("启动") }
        item(key = "appearance-startup-tab") {
            TvChoiceRow(
                title = "启动进入",
                options = StartupTab.entries,
                selected = startupTab,
                label = { it.label },
                stableId = "appearance:startup-tab",
                focusMemory = focusMemory,
                onSelect = prefs::setStartupTab,
                icon = AppIcons.Home,
                focusScope = focusScope,
                subtitle = startupTab.description,
                navigationRequester = navigationRequester,
            )
        }

        item(key = "appearance-section-a11y") { TvSettingsSectionTitle("辅助功能") }
        item(key = "appearance-large-text") {
            TvToggleRow(
                title = "大号文字",
                checked = largeText,
                stableId = "appearance:large-text",
                focusMemory = focusMemory,
                onToggle = prefs::setLargeText,
                icon = AppIcons.Info,
                focusScope = focusScope,
                subtitle = "把界面文字整体放大，便于远距离阅读",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "appearance-reduce-motion") {
            TvToggleRow(
                title = "减少动态效果",
                checked = reduceMotion,
                stableId = "appearance:reduce-motion",
                focusMemory = focusMemory,
                onToggle = prefs::setReduceMotion,
                icon = AppIcons.SkipMarkers,
                focusScope = focusScope,
                subtitle = "关闭首页大图轮播与焦点缩放动画",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "appearance-reduce-transparency") {
            TvToggleRow(
                title = "减少透明度",
                checked = reduceTransparency,
                stableId = "appearance:reduce-transparency",
                focusMemory = focusMemory,
                onToggle = prefs::setReduceTransparency,
                icon = AppIcons.Eye,
                focusScope = focusScope,
                subtitle = "面板改用实心底色，文字对比度更高",
                navigationRequester = navigationRequester,
            )
        }
    }
}

/**
 * Splash animation only.
 *
 * The phone also switches its launcher icon here. That works by enabling one of several
 * `activity-alias` entries, and the television package declares none: a leanback launcher shows
 * the banner, not an icon, so the aliases would have nothing to change.
 */
@Composable
internal fun TvSplashSettingsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:splash"
    val prefs = component.themePreferences
    val splashEnabled by prefs.splashAnimation.collectAsState()
    val splashVariant by prefs.splashVariant.collectAsState()

    TvSettingsPageScaffold(page = TvSettingsPage.Splash) {
        item(key = "splash-enabled") {
            TvToggleRow(
                title = "播放开屏动画",
                checked = splashEnabled,
                stableId = "splash:enabled",
                focusMemory = focusMemory,
                onToggle = prefs::setSplashAnimation,
                icon = AppIcons.Play,
                focusScope = focusScope,
                subtitle = "关闭后启动会直接进入内容",
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "splash-variant") {
            TvChoiceRow(
                title = "动画样式",
                options = SplashAnimation.entries,
                selected = splashVariant,
                label = { it.label },
                stableId = "splash:variant",
                focusMemory = focusMemory,
                onSelect = prefs::setSplashVariant,
                icon = AppIcons.Star,
                focusScope = focusScope,
                subtitle = splashVariant.description,
                enabled = splashEnabled,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "splash-icon-note") {
            TvSettingsNote(
                "手机端还可以在这里更换启动器图标。电视版没有这一项：leanback 启动器显示的是横幅，" +
                    "电视安装包也没有声明可切换的图标别名。",
            )
        }
    }
}
