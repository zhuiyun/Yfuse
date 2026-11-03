package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.MotionTheme
import com.yfuse.core.designsystem.Section
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.SettingsDivider
import com.yfuse.core.designsystem.SwitchRow
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.platformAnimationsDisabled
import com.yfuse.core.widget.widgetRepository
import com.yfuse.feature.widgets.WidgetWorkspaceScreen

internal enum class AppearanceAction {
    Background,
    Startup,
    MotionTheme,
    DialogAnimation,
    LoadingAnimation,
    PlayerTransition,
    ParticleLight,
}

/** Each page keeps one purpose; all controls still write the original preference keys. */
@Composable
internal fun AppearanceSettingsScreen(
    page: ProfilePage,
    preferences: ThemePreferences,
    brandSummary: String,
    onBack: () -> Unit,
    onOpenPage: (ProfilePage) -> Unit,
    onAction: (AppearanceAction) -> Unit,
) {
    when (page) {
        ProfilePage.AppearanceLayout -> LayoutSettingsPage(preferences, onBack, onAction)
        ProfilePage.AppearanceMotion -> MotionSettingsPage(preferences, onBack, onAction)
        ProfilePage.AppearanceAccessibility -> AccessibilitySettingsPage(preferences, onBack)
        else -> AppearanceOverview(preferences, brandSummary, onBack, onOpenPage, onAction)
    }
}

@Composable
private fun AppearanceOverview(
    prefs: ThemePreferences,
    brandSummary: String,
    onBack: () -> Unit,
    onOpenPage: (ProfilePage) -> Unit,
    onAction: (AppearanceAction) -> Unit,
) {
    val mode by prefs.mode.collectAsState()
    val glass by prefs.glassStyle.collectAsState()
    val background by prefs.backgroundImage.collectAsState()
    val motion by prefs.motionTheme.collectAsState()
    val modes = listOf(ThemeMode.Light, ThemeMode.System, ThemeMode.Dark)
    SettingsPage(title = "外观与交互", onBack = onBack) {
        motionItem(key = "appearance-theme") {
            Section("主题") {
                SettingsCard {
                    SettingSegmentRow(
                        "主题",
                        modes.map { it.label },
                        modes.indexOf(mode),
                        { prefs.setMode(modes[it]) },
                        AppIcons.Cloud,
                        SettingTint.appearance,
                    )
                    SettingsDivider()
                    SettingSegmentRow(
                        "视觉效果",
                        GlassStyle.entries.map { it.label },
                        GlassStyle.entries.indexOf(glass),
                        { prefs.setGlassStyle(GlassStyle.entries[it]) },
                        AppIcons.Grid,
                        SettingTint.components,
                    )
                }
            }
        }
        motionItem(key = "appearance-style") {
            Section("背景与图标") {
                SettingsCard {
                    SettingRow(
                        "背景图",
                        if (background == null) "未设置" else "已设置",
                        true,
                        { onAction(AppearanceAction.Background) },
                        AppIcons.AspectFit,
                        SettingTint.appearance,
                    )
                    SettingsDivider()
                    SettingRow(
                        "玻璃材质",
                        "底色与透明度",
                        true,
                        { onOpenPage(ProfilePage.GlassMaterial) },
                        AppIcons.AspectFill,
                        SettingTint.components,
                    )
                    SettingsDivider()
                    SettingRow(
                        "Logo 与开屏动画",
                        brandSummary,
                        true,
                        { onOpenPage(ProfilePage.Splash) },
                        AppIcons.StarFilled,
                        SettingTint.appearance,
                    )
                }
            }
        }
        motionItem(key = "appearance-pages") {
            Section("布局与交互") {
                SettingsCard {
                    SettingRow(
                        "首页与导航",
                        "启动页、组件与导航栏",
                        true,
                        { onOpenPage(ProfilePage.AppearanceLayout) },
                        AppIcons.Home,
                        SettingTint.components,
                    )
                    SettingsDivider()
                    SettingRow(
                        "动画与动效",
                        motion.label,
                        true,
                        { onOpenPage(ProfilePage.AppearanceMotion) },
                        AppIcons.Movie,
                        SettingTint.components,
                    )
                    SettingsDivider()
                    SettingRow(
                        "辅助功能",
                        "文字、透明度与减少动画",
                        true,
                        { onOpenPage(ProfilePage.AppearanceAccessibility) },
                        AppIcons.Subtitle,
                        SettingTint.language,
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutSettingsPage(
    prefs: ThemePreferences,
    onBack: () -> Unit,
    onAction: (AppearanceAction) -> Unit,
) {
    val startup by prefs.startupTab.collectAsState()
    val customHome by prefs.customHomeVisible.collectAsState()
    val carousel by prefs.libraryCarousel.collectAsState()
    val collapse by prefs.navCollapseOnScroll.collectAsState()
    var manageWidgets by remember { mutableStateOf(false) }
    if (manageWidgets) WidgetWorkspaceScreen(remember { widgetRepository() }, onClose = { manageWidgets = false })
    SettingsPage(title = "首页与导航", onBack = onBack) {
        motionItem(key = "layout-home") {
            Section("首页") {
                SettingsCard {
                    SettingRow(
                        "启动进入",
                        startup.label,
                        true,
                        { onAction(AppearanceAction.Startup) },
                        AppIcons.Home,
                        SettingTint.general,
                    )
                    SettingsDivider()
                    SwitchRow(
                        "自定义首页 Tab",
                        customHome,
                        true,
                        icon = AppIcons.Home,
                        iconTint = SettingTint.components,
                        description = "关闭后保留页面和组件",
                        onChange = prefs::setCustomHomeVisible,
                    )
                    SettingsDivider()
                    SettingRow(
                        "首页与组件管理",
                        "布局、组件与备份",
                        true,
                        { manageWidgets = true },
                        AppIcons.Grid,
                        SettingTint.components,
                    )
                    SettingsDivider()
                    WidgetCutoutSettingsRow()
                }
            }
        }
        motionItem(key = "layout-navigation") {
            Section("浏览与导航") {
                SettingsCard {
                    SwitchRow(
                        "库页轮播图",
                        carousel,
                        true,
                        icon = AppIcons.Grid,
                        iconTint = SettingTint.library,
                        onChange = prefs::setLibraryCarousel,
                    )
                    SettingsDivider()
                    SwitchRow(
                        "滚动时收起导航栏",
                        collapse,
                        true,
                        icon = AppIcons.Collapse,
                        iconTint = SettingTint.general,
                        description = "向下收起，回滑或点击展开",
                        onChange = prefs::setNavCollapseOnScroll,
                    )
                }
            }
        }
    }
}

@Composable
private fun MotionSettingsPage(
    prefs: ThemePreferences,
    onBack: () -> Unit,
    onAction: (AppearanceAction) -> Unit,
) {
    val theme by prefs.motionTheme.collectAsState()
    val reduced by prefs.reduceMotion.collectAsState()
    val systemOff = platformAnimationsDisabled()
    SettingsPage(title = "动画与动效", onBack = onBack) {
        motionItem(key = "motion-theme") {
            Section("整体风格") {
                SettingsCard {
                    SettingRow(
                        "动效主题",
                        theme.label,
                        true,
                        { onAction(AppearanceAction.MotionTheme) },
                        AppIcons.Movie,
                        SettingTint.components,
                        supporting = if (reduced || systemOff) "当前已减少动画，关闭后应用所选风格" else theme.description,
                        trailingLabel = theme.label,
                    )
                }
            }
        }
        motionItem(key = "motion-details") {
            Section("单项动效") { MotionDetailRows(prefs, theme, reduced || systemOff, onAction) }
        }
    }
}

@Composable
private fun MotionDetailRows(
    prefs: ThemePreferences,
    theme: MotionTheme,
    reduced: Boolean,
    onAction: (AppearanceAction) -> Unit,
) {
    val pulse by prefs.pulseSweep.collectAsState()
    val particles by prefs.particleLight.collectAsState()
    val dialog by prefs.dialogAnimation.collectAsState()
    val loading by prefs.loadingAnimation.collectAsState()
    val transition by prefs.playerTransition.collectAsState()
    val enabled = !reduced && theme != MotionTheme.Calm
    val reason =
        when {
            reduced -> "减少动画已生效"
            theme == MotionTheme.Calm -> "跟随静息主题"
            else -> null
        }
    SettingsCard {
        SwitchRow(
            "搜索与导航动效",
            pulse,
            true,
            icon = AppIcons.SearchTab,
            iconTint = SettingTint.components,
            enabled = enabled,
            description = reason,
            onChange = prefs::setPulseSweep,
        )
        SettingsDivider()
        SettingRow(
            "粒子光效",
            reason ?: particles.label,
            true,
            { onAction(AppearanceAction.ParticleLight) },
            AppIcons.StarFilled,
            SettingTint.components,
            enabled = enabled,
        )
        SettingsDivider()
        SettingRow(
            "弹窗动画",
            reason ?: dialog.label,
            true,
            { onAction(AppearanceAction.DialogAnimation) },
            AppIcons.Expand,
            SettingTint.components,
            enabled = enabled,
        )
        SettingsDivider()
        SettingRow(
            "加载动画",
            reason ?: loading.label,
            true,
            { onAction(AppearanceAction.LoadingAnimation) },
            AppIcons.Refresh,
            SettingTint.components,
            enabled = enabled,
        )
        SettingsDivider()
        SettingRow(
            "播放器进出场",
            reason ?: if (theme == MotionTheme.Cinema) "跟随影院主题" else transition.label,
            true,
            { onAction(AppearanceAction.PlayerTransition) },
            AppIcons.Play,
            SettingTint.components,
            enabled = enabled && theme == MotionTheme.Classic,
        )
    }
}

@Composable
private fun AccessibilitySettingsPage(
    prefs: ThemePreferences,
    onBack: () -> Unit,
) {
    val transparency by prefs.reduceTransparency.collectAsState()
    val largeText by prefs.largeText.collectAsState()
    val reduced by prefs.reduceMotion.collectAsState()
    val systemOff = platformAnimationsDisabled()
    SettingsPage(title = "辅助功能", onBack = onBack) {
        motionItem(key = "accessibility-display") {
            Section("显示") {
                SettingsCard {
                    SwitchRow(
                        "大号文字",
                        largeText,
                        true,
                        icon = AppIcons.Subtitle,
                        iconTint = SettingTint.language,
                        onChange = prefs::setLargeText,
                    )
                    SettingsDivider()
                    SwitchRow(
                        "减少透明效果",
                        transparency,
                        true,
                        icon = AppIcons.AspectFill,
                        iconTint = SettingTint.components,
                        onChange = prefs::setReduceTransparency,
                    )
                }
            }
        }
        motionItem(key = "accessibility-motion") {
            Section("动画") {
                SettingsCard {
                    SwitchRow(
                        "减少动画",
                        reduced,
                        true,
                        icon = AppIcons.Pause,
                        iconTint = SettingTint.advanced,
                        description = if (systemOff) "系统已关闭动画，应用自动遵循" else "保留必要反馈，减少页面与装饰动画",
                        onChange = prefs::setReduceMotion,
                    )
                }
            }
        }
    }
}
