package com.yfuse.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.BuildConfig
import com.yfuse.core.account.AccountState
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.feature.profile.ProfileComponent
import com.yfuse.feature.profile.ProfileIntent
import com.yfuse.tv.focus.requestFocusWhenAttached

/**
 * Settings root plus its sub-pages.
 *
 * The navigation rail stays mounted while a sub-page is open, so this is a page swap rather than a
 * Decompose stack push. Back is handled here so it unwinds the sub-page before the shell's own
 * handler moves the viewer to the Home tab.
 */
@Composable
internal fun TvSettingsScreen(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
) {
    var page by rememberSaveable { mutableStateOf(TvSettingsPage.Root) }
    val pageRequester = remember { FocusRequester() }

    BackHandler(enabled = page != TvSettingsPage.Root) { page = TvSettingsPage.Root }

    // A page swap leaves focus on a row that no longer exists, which strands the remote. Pulling
    // focus to the new page's first row keeps every transition navigable.
    LaunchedEffect(page) {
        if (page != TvSettingsPage.Root) pageRequester.requestFocusWhenAttached()
    }

    when (page) {
        TvSettingsPage.Root ->
            TvSettingsRootPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                contentRequester = contentRequester,
                onOpen = { page = it },
            )
        TvSettingsPage.Account ->
            TvAccountSettingsPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
                onOpenSessions = { page = TvSettingsPage.AccountSessions },
            )
        TvSettingsPage.AccountSessions ->
            TvAccountSessionsPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
        TvSettingsPage.Playback ->
            TvPlaybackSettingsPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
        TvSettingsPage.AdvancedPlayback ->
            TvAdvancedPlaybackSettingsPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
        TvSettingsPage.Danmaku ->
            TvDanmakuSettingsPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
        TvSettingsPage.WatchTogether ->
            TvWatchTogetherSettingsPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
        TvSettingsPage.Appearance ->
            TvAppearanceSettingsPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
        TvSettingsPage.Splash ->
            TvSplashSettingsPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
        TvSettingsPage.ServerBackup ->
            TvServerBackupPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
        TvSettingsPage.PermissionHealth ->
            TvPermissionHealthPage(
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
        TvSettingsPage.Downloads ->
            TvDownloadsPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
        TvSettingsPage.DataAndDiagnostics ->
            TvDataDiagnosticsPage(
                component = component,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                firstRowRequester = pageRequester,
            )
    }
}

@Composable
private fun TvSettingsRootPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
    onOpen: (TvSettingsPage) -> Unit,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val mode by component.themePreferences.mode.collectAsState()
    val largeText by component.themePreferences.largeText.collectAsState()
    val reduceMotion by component.themePreferences.reduceMotion.collectAsState()
    val dialogAnimation by component.themePreferences.dialogAnimation.collectAsState()
    val autoNext by component.themePreferences.autoNext.collectAsState()
    val account by component.account.state.collectAsState()
    val danmakuEnabled by component.danmakuPreferences.enabled.collectAsState()
    val downloads by component.offlineMedia.items.collectAsState()
    val downloadCount = downloads.size
    val scope = "settings"
    var query by rememberSaveable { mutableStateOf("") }

    TvRestoreRouteFocusEffect(
        route = "settings",
        focusMemory = focusMemory,
        fallback = contentRequester,
        contentGeneration = listOf(state.currentServer?.id, mode, largeText, reduceMotion, account::class),
    )

    TvSettingsPageScaffold(page = TvSettingsPage.Root) {
        item(key = "settings-subtitle") {
            Column {
                Text(
                    state.currentServer?.let { "${it.serverName} · ${it.userName}" }
                        ?: "尚未连接服务器",
                    color = TvOnSurfaceMuted,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        // Fourteen pages is more than a remote should have to walk. Typing two characters is
        // faster than pressing down eleven times.
        item(key = "settings-search") {
            TvSettingsTextField(
                value = query,
                label = "搜索设置",
                stableId = "settings:search",
                focusScope = scope,
                focusMemory = focusMemory,
                onValueChange = { query = it.take(40) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        if (query.isNotBlank()) {
            val matches = searchTvSettings(query)
            if (matches.isEmpty()) {
                item(key = "settings-search-empty") {
                    TvSettingsNote("没有匹配的设置项。")
                }
            }
            matches.forEach { page ->
                item(key = "settings-search-hit:${page.name}") {
                    TvSettingRow(
                        title = page.title,
                        value = "",
                        stableId = "settings:search:${page.name}",
                        focusMemory = focusMemory,
                        onClick = {
                            query = ""
                            onOpen(page)
                        },
                        icon = AppIcons.Search,
                        focusScope = scope,
                        subtitle = page.subtitle,
                        navigationRequester = navigationRequester,
                    )
                }
            }
        }

        item(key = "settings-section-server") {
            TvSettingsSectionTitle("服务器与账号")
        }
        item(key = "settings-servers") {
            TvSettingRow(
                title = "服务器",
                value = "${state.serverCount} 台",
                stableId = "settings:servers",
                focusMemory = focusMemory,
                onClick = component.onOpenServers,
                icon = AppIcons.TabServers,
                focusScope = scope,
                subtitle = "添加、切换与登出媒体服务器",
                focusRequester = contentRequester,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-account") {
            TvSettingRow(
                title = TvSettingsPage.Account.title,
                value = account.shortLabel(),
                stableId = "settings:account",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.Account) },
                icon = AppIcons.User,
                focusScope = scope,
                subtitle = TvSettingsPage.Account.subtitle,
                navigationRequester = navigationRequester,
            )
        }

        item(key = "settings-section-appearance") { TvSettingsSectionTitle("外观") }
        // 界面模式 stays on the root because it is the one appearance control people change
        // often; everything else lives on its own page rather than lengthening this list.
        item(key = "settings-theme") {
            TvChoiceRow(
                title = "界面模式",
                options = ThemeMode.entries,
                selected = mode,
                label = { it.label },
                stableId = "settings:theme",
                focusMemory = focusMemory,
                onSelect = component.themePreferences::setMode,
                icon = AppIcons.Grid,
                focusScope = scope,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-appearance") {
            TvSettingRow(
                title = TvSettingsPage.Appearance.title,
                value =
                    listOfNotNull(
                        dialogAnimation.label,
                        "大号文字".takeIf { largeText },
                        "减少动效".takeIf { reduceMotion },
                    ).joinToString(" · "),
                stableId = "settings:appearance",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.Appearance) },
                icon = AppIcons.Expand,
                focusScope = scope,
                subtitle = TvSettingsPage.Appearance.subtitle,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-splash") {
            TvSettingRow(
                title = TvSettingsPage.Splash.title,
                value = "",
                stableId = "settings:splash",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.Splash) },
                icon = AppIcons.Star,
                focusScope = scope,
                subtitle = TvSettingsPage.Splash.subtitle,
                navigationRequester = navigationRequester,
            )
        }

        item(key = "settings-section-playback") { TvSettingsSectionTitle("播放") }
        item(key = "settings-auto-next") {
            TvToggleRow(
                title = "自动播放下一集",
                checked = autoNext,
                stableId = "settings:auto-next",
                focusMemory = focusMemory,
                onToggle = component.themePreferences::setAutoNext,
                icon = AppIcons.Next,
                focusScope = scope,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-playback") {
            TvSettingRow(
                title = TvSettingsPage.Playback.title,
                value = "",
                stableId = "settings:playback",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.Playback) },
                icon = AppIcons.Play,
                focusScope = scope,
                subtitle = TvSettingsPage.Playback.subtitle,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-advanced-playback") {
            TvSettingRow(
                title = TvSettingsPage.AdvancedPlayback.title,
                value = "",
                stableId = "settings:advanced-playback",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.AdvancedPlayback) },
                icon = AppIcons.PlaybackSource,
                focusScope = scope,
                subtitle = TvSettingsPage.AdvancedPlayback.subtitle,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-danmaku") {
            TvSettingRow(
                title = TvSettingsPage.Danmaku.title,
                value = if (danmakuEnabled) "已开启" else "已关闭",
                stableId = "settings:danmaku",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.Danmaku) },
                icon = AppIcons.Danmaku,
                focusScope = scope,
                subtitle = TvSettingsPage.Danmaku.subtitle,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-watch-together") {
            TvSettingRow(
                title = TvSettingsPage.WatchTogether.title,
                value = "",
                stableId = "settings:watch-together",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.WatchTogether) },
                icon = AppIcons.Chat,
                focusScope = scope,
                subtitle = TvSettingsPage.WatchTogether.subtitle,
                navigationRequester = navigationRequester,
            )
        }

        item(key = "settings-section-support") { TvSettingsSectionTitle("数据与支持") }
        item(key = "settings-downloads") {
            TvSettingRow(
                title = TvSettingsPage.Downloads.title,
                value = if (downloadCount > 0) "$downloadCount 项" else "",
                stableId = "settings:downloads",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.Downloads) },
                icon = AppIcons.Download,
                focusScope = scope,
                subtitle = TvSettingsPage.Downloads.subtitle,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-server-backup") {
            TvSettingRow(
                title = TvSettingsPage.ServerBackup.title,
                value = "",
                stableId = "settings:server-backup",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.ServerBackup) },
                icon = AppIcons.Cloud,
                focusScope = scope,
                subtitle = TvSettingsPage.ServerBackup.subtitle,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-permissions") {
            TvSettingRow(
                title = TvSettingsPage.PermissionHealth.title,
                value = "",
                stableId = "settings:permissions",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.PermissionHealth) },
                icon = AppIcons.Lock,
                focusScope = scope,
                subtitle = TvSettingsPage.PermissionHealth.subtitle,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-diagnostics") {
            TvSettingRow(
                title = TvSettingsPage.DataAndDiagnostics.title,
                value = "",
                stableId = "settings:diagnostics",
                focusMemory = focusMemory,
                onClick = { onOpen(TvSettingsPage.DataAndDiagnostics) },
                icon = AppIcons.Info,
                focusScope = scope,
                subtitle = TvSettingsPage.DataAndDiagnostics.subtitle,
                navigationRequester = navigationRequester,
            )
        }

        if (state.servers.size > 1) {
            item(key = "settings-switch-title") { TvSettingsSectionTitle("快速切换服务器") }
            state.servers.forEach { server ->
                item(key = "settings-server:${server.id}") {
                    TvSettingRow(
                        title = server.serverName,
                        value = server.userName,
                        stableId = "settings:server:${server.id}",
                        focusMemory = focusMemory,
                        onClick = { component.store.accept(ProfileIntent.SwitchServer(server.id)) },
                        icon = AppIcons.Server,
                        focusScope = scope,
                        selected = state.currentServer?.id == server.id,
                        navigationRequester = navigationRequester,
                    )
                }
            }
        }

        item(key = "settings-version") {
            Column {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Yfuse for Android TV · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    color = TvOnSurfaceMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Matches a typed query against the settings pages.
 *
 * Keywords carry the words a viewer is likely to type but which do not appear in a page title:
 * someone looking for subtitles types 字幕, and the page that owns it is called 弹幕.
 */
private val tvSettingsKeywords: Map<TvSettingsPage, String> =
    mapOf(
        TvSettingsPage.Account to "登录 注册 同步 云端 密码 会话",
        TvSettingsPage.AccountSessions to "设备 退出 撤销 登录记录",
        TvSettingsPage.Playback to "进度 续播 片头 片尾 跳过 选源 隐私",
        TvSettingsPage.AdvancedPlayback to "内核 解码 缓冲 缓存 帧率 直通 音频 硬解 软解 ycore",
        TvSettingsPage.Danmaku to "弹幕 字幕 屏蔽 过滤 字号 透明",
        TvSettingsPage.WatchTogether to "一起看 房间 聊天 昵称 头像",
        TvSettingsPage.Appearance to "主题 深色 浅色 背景 玻璃 字体 大字 动效 启动 无障碍",
        TvSettingsPage.Splash to "开屏 动画 logo 启动画面",
        TvSettingsPage.Downloads to "下载 离线 队列 存储 空间 wifi",
        TvSettingsPage.ServerBackup to "备份 导出 导入 迁移 换机 口令",
        TvSettingsPage.PermissionHealth to "权限 通知 局域网 授权",
        TvSettingsPage.DataAndDiagnostics to "日志 诊断 导出 缓存 清除",
    )

internal fun searchTvSettings(query: String): List<TvSettingsPage> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    return TvSettingsPage.entries
        .filter { it != TvSettingsPage.Root }
        .filter { page ->
            page.title.lowercase().contains(needle) ||
                page.subtitle.lowercase().contains(needle) ||
                tvSettingsKeywords[page].orEmpty().lowercase().contains(needle)
        }
}

/** Short account status for the settings root row. */
internal fun AccountState.shortLabel(): String =
    when (this) {
        AccountState.SignedOut -> "未登录"
        AccountState.Restoring -> "正在恢复"
        is AccountState.RestoreFailed -> "恢复失败"
        is AccountState.SignedIn -> session.user.nickname.ifBlank { session.user.username }
    }
