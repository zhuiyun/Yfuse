package com.yfuse.feature.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.floatingNavigationContentInset
import com.yfuse.app.systemNavigationContentInset
import com.yfuse.core.account.AccountState
import com.yfuse.core.account.canUseMediaDiscovery
import com.yfuse.core.account.canUseWatchTogether
import com.yfuse.core.data.DanmakuSource
import com.yfuse.core.data.MediaVersionPreference
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.VideoCacheSize
import com.yfuse.core.data.YCoreBufferDuration
import com.yfuse.core.data.activeOr
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.ReportOverlayVisible
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.SplashPreview
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.WindowWidthTier
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.defaultAnimation
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.windowWidthTier
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.StartupTab
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.offline.offlinePlaybackUri
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.PlayerMediaItem
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.flatGlass as glass

/** Which option sheet is open. Theme and glass style are answered in place on the root page. */
private enum class Sheet {
    StartupTab,
    Background,
    PlaybackMode,
    MediaVersionPreference,
    AdvancedPlaybackMode,
    Engine,
    Decoder,
    YCoreBufferDuration,
    DanmakuSource,
    DanmakuBlocked,
    SkipSegments,
    UserAgent,
    WatchTogether,
    WatchProfile,
    VideoCache,
}

/** Light to dark, which is how the segmented control is read left to right. */
private val ThemeModeDisplayOrder = listOf(ThemeMode.Light, ThemeMode.System, ThemeMode.Dark)

private enum class ProfilePage {
    Root,
    Account,
    AccountSessions,
    Playback,
    AdvancedPlayback,
    Danmaku,
    WatchTogether,
    Appearance,
    MediaDiscovery,
    DataAndDiagnostics,
    Downloads,
    Splash,
}

private data class SettingsSearchDestination(
    val title: String,
    val summary: String,
    val keywords: String,
    val page: ProfilePage? = null,
    val opensServers: Boolean = false,
    val icon: ImageVector,
    val tint: Color,
)

private val SettingsSearchDestinations =
    listOf(
        SettingsSearchDestination(
            "账号与同步",
            "登录、会话与加密同步",
            "账号 登录 会话 同步",
            ProfilePage.Account,
            icon = AppIcons.User,
            tint = SettingTint.account,
        ),
        SettingsSearchDestination(
            "服务器",
            "连接与切换媒体服务器",
            "服务器 emby jellyfin plex",
            opensServers = true,
            icon = AppIcons.Server,
            tint = SettingTint.servers,
        ),
        SettingsSearchDestination(
            "外观与主题",
            "主题、背景、动效与辅助功能",
            "外观 主题 背景 玻璃 字体 动效",
            ProfilePage.Appearance,
            icon = AppIcons.Grid,
            tint = SettingTint.appearance,
        ),
        SettingsSearchDestination(
            "播放",
            "版本偏好、画质、引擎、进度同步与跳过片头",
            "播放 版本 HDR 杜比 画质 解码 引擎 续播 进度 同步",
            ProfilePage.Playback,
            icon = AppIcons.Play,
            tint = SettingTint.playback,
        ),
        SettingsSearchDestination(
            "字幕与弹幕",
            "来源、关键词屏蔽与显示",
            "字幕 弹幕 api 简繁 画中画",
            ProfilePage.Danmaku,
            icon = AppIcons.Danmaku,
            tint = SettingTint.danmaku,
        ),
        SettingsSearchDestination(
            "下载",
            "下载任务与离线媒体库",
            "下载 离线 缓存",
            ProfilePage.Downloads,
            icon = AppIcons.Download,
            tint = SettingTint.downloads,
        ),
        SettingsSearchDestination(
            "影视发现与追剧日历",
            "榜单、日历与转存",
            "发现 榜单 日历 追剧 123",
            ProfilePage.MediaDiscovery,
            icon = AppIcons.Cloud,
            tint = SettingTint.sync,
        ),
        SettingsSearchDestination(
            "高级设置",
            "网络兼容、备份、缓存与诊断",
            "高级 网络 备份 缓存 诊断",
            ProfilePage.DataAndDiagnostics,
            icon = AppIcons.Info,
            tint = SettingTint.advanced,
        ),
    )

@Composable
fun ProfileScreen(component: ProfileComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val prefs = component.themePreferences
    val mode by prefs.mode.collectAsState()
    val reduceTransparency by prefs.reduceTransparency.collectAsState()
    val largeText by prefs.largeText.collectAsState()
    val reduceMotion by prefs.reduceMotion.collectAsState()
    val decoder by prefs.decoder.collectAsState()
    val autoNext by prefs.autoNext.collectAsState()
    val splashAnimation by prefs.splashAnimation.collectAsState()
    val splashVariant by prefs.splashVariant.collectAsState()
    val startupTab by prefs.startupTab.collectAsState()
    val glassStyle by prefs.glassStyle.collectAsState()
    val backgroundImage by prefs.backgroundImage.collectAsState()
    val backgroundDim by prefs.backgroundDim.collectAsState()
    var appIcon by remember { mutableStateOf(currentAppIconVariant()) }
    val videoCacheSize by component.playbackPreferences.videoCacheSize.collectAsState()
    val yCoreBufferDuration by component.playbackPreferences.yCoreBufferDuration.collectAsState()
    val optimizationMode by component.playbackPreferences.optimizationMode.collectAsState()
    val mediaVersionPreference by component.playbackPreferences.mediaVersionPreference.collectAsState()
    val engineSelection by component.playbackPreferences.engineSelection.collectAsState()
    val smartCrossServerSource by component.playbackPreferences.smartCrossServerSource.collectAsState()
    val anonymousQoeSharing by component.playbackPreferences.anonymousQoeSharing.collectAsState()
    val progressSyncEnabled by component.dependencies.serverSyncManager.syncProgress.collectAsState()
    val watchTogether = component.watchTogether
    val watchState by watchTogether.state.collectAsState()
    val watchEndpoint by component.watchTogetherPreferences.endpoint.collectAsState()
    val watchNickname by component.watchTogetherPreferences.nickname.collectAsState()
    val watchAvatarId by component.watchTogetherPreferences.avatarId.collectAsState()
    val watchChatPreview by component.watchTogetherPreferences.chatPreviewEnabled.collectAsState()
    val watchChatDanmaku by component.watchTogetherPreferences.chatDanmakuEnabled.collectAsState()
    val danmakuSources by component.danmakuPreferences.sources.collectAsState()
    val danmakuActiveSourceId by component.danmakuPreferences.activeSourceId.collectAsState()
    val danmakuBlocked by component.danmakuPreferences.blockedWords.collectAsState()
    val skipTimesBySeries by component.skipSegmentPreferences.bySeries.collectAsState()
    val skipMode by component.skipSegmentPreferences.skipMode.collectAsState()
    val customUserAgent by component.userAgentPreferences.customValue.collectAsState()
    val offlineItems by component.offlineMedia.items.collectAsState()
    val accountState by component.account.state.collectAsState()
    val discoverySettingsRequest by component.tgtoMediaPreferences.openSettingsRequest.collectAsState()
    val watchAvailable = accountState.canUseWatchTogether()
    val discoveryAvailable = accountState.canUseMediaDiscovery()

    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var confirmClearVideoCache by remember { mutableStateOf(false) }
    var videoCacheUsageBytes by remember { mutableStateOf<Long?>(null) }
    var pageStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    var offlineToPlay by remember { mutableStateOf<OfflineMedia?>(null) }
    val palette = LocalPalette.current
    val mainListState = rememberLazyListState()
    val rootBottomContentInset = floatingNavigationContentInset()
    ScrollToTopOnReselect(mainListState)
    val screenScope = rememberCoroutineScope()

    fun openPage(target: ProfilePage) {
        pageStack = pageStack + target.name
    }

    fun closePage() {
        pageStack = pageStack.dropLast(1)
    }

    StatusBarIconStyle(darkIcons = !palette.isDark)
    ReportOverlayVisible(enabled = pageStack.isNotEmpty())

    LaunchedEffect(watchAvailable) {
        if (!watchAvailable) {
            if (sheet == Sheet.WatchTogether || sheet == Sheet.WatchProfile) sheet = null
            if (pageStack.lastOrNull() == ProfilePage.WatchTogether.name) closePage()
        }
    }

    LaunchedEffect(discoveryAvailable, accountState) {
        if (
            accountState !is AccountState.Restoring &&
            !discoveryAvailable &&
            pageStack.lastOrNull() == ProfilePage.MediaDiscovery.name
        ) {
            closePage()
        }
    }

    LaunchedEffect(discoverySettingsRequest, discoveryAvailable) {
        if (discoverySettingsRequest > 0L) {
            if (
                discoveryAvailable &&
                pageStack.lastOrNull() != ProfilePage.MediaDiscovery.name
            ) {
                openPage(ProfilePage.MediaDiscovery)
            }
            component.tgtoMediaPreferences.consumeOpenSettingsRequest()
        }
    }

    LaunchedEffect(pageStack.lastOrNull(), videoCacheSize) {
        if (pageStack.lastOrNull() == ProfilePage.DataAndDiagnostics.name) {
            videoCacheUsageBytes = component.videoCacheUsageBytes()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val navigationBackStack = remember(pageStack) { listOf(ProfilePage.Root) + pageStack.map(ProfilePage::valueOf) }
        OfficialNavDisplay(
            backStack = navigationBackStack,
            onBack = ::closePage,
            contentKey = ProfilePage::name,
            modifier = Modifier.fillMaxSize(),
        ) { activePage ->
            when (activePage) {
                ProfilePage.Account ->
                    AccountSettingsScreen(
                        account = component.account,
                        onBack = ::closePage,
                        onOpenSessions = { openPage(ProfilePage.AccountSessions) },
                    )

                ProfilePage.AccountSessions ->
                    AccountSessionsScreen(
                        account = component.account,
                        onBack = ::closePage,
                    )

                ProfilePage.Playback ->
                    PlaybackSettingsScreen(
                        onBack = ::closePage,
                        optimizationMode = optimizationMode,
                        mediaVersionPreference = mediaVersionPreference,
                        autoNext = autoNext,
                        smartCrossServerSource = smartCrossServerSource,
                        progressSyncEnabled = progressSyncEnabled,
                        anonymousQoeSharing = anonymousQoeSharing,
                        videoCacheSize = videoCacheSize,
                        skipSegments =
                            if (skipTimesBySeries.isEmpty()) {
                                "${skipMode.label} · 跟随服务器 ›"
                            } else {
                                "${skipMode.label} ›"
                            },
                        onPlaybackMode = { sheet = Sheet.PlaybackMode },
                        onMediaVersionPreference = { sheet = Sheet.MediaVersionPreference },
                        onOpenAdvanced = { openPage(ProfilePage.AdvancedPlayback) },
                        onAutoNext = prefs::setAutoNext,
                        onSmartCrossServerSource = component.playbackPreferences::setSmartCrossServerSource,
                        onProgressSync = component.dependencies.serverSyncManager::setProgress,
                        onAnonymousQoeSharing = component.playbackPreferences::setAnonymousQoeSharing,
                        onVideoCache = { sheet = Sheet.VideoCache },
                        onSkipSegments = { sheet = Sheet.SkipSegments },
                    )

                ProfilePage.AdvancedPlayback ->
                    AdvancedPlaybackSettingsScreen(
                        onBack = ::closePage,
                        optimizationMode = optimizationMode,
                        engineSelection = engineSelection,
                        decoder = decoder,
                        yCoreBufferDuration = yCoreBufferDuration,
                        onOptimizationMode = { sheet = Sheet.AdvancedPlaybackMode },
                        onEngine = { sheet = Sheet.Engine },
                        onDecoder = { sheet = Sheet.Decoder },
                        onYCoreBufferDuration = { sheet = Sheet.YCoreBufferDuration },
                    )

                ProfilePage.Danmaku ->
                    DanmakuSettingsScreen(
                        onBack = ::closePage,
                        sourceSummary =
                            when (danmakuSources.size) {
                                0 -> "未配置 ›"
                                1 -> "${danmakuSources.first().name} ›"
                                else -> {
                                    val active = danmakuSources.activeOr(danmakuActiveSourceId)
                                    "${danmakuSources.size} 个 · ${active?.name.orEmpty()} ›"
                                }
                            },
                        blockedSummary = if (danmakuBlocked.isEmpty()) "未设置 ›" else "${danmakuBlocked.size} 个 ›",
                        onSources = { sheet = Sheet.DanmakuSource },
                        onBlockedWords = { sheet = Sheet.DanmakuBlocked },
                    )

                ProfilePage.WatchTogether ->
                    if (watchAvailable) {
                        WatchTogetherSettingsScreen(
                            onBack = ::closePage,
                            connected = watchState.connected,
                            roomCode = watchState.roomCode,
                            nickname = watchNickname,
                            chatDanmaku = watchChatDanmaku,
                            chatPreview = watchChatPreview,
                            onJoin = { sheet = Sheet.WatchTogether },
                            onProfile = { sheet = Sheet.WatchProfile },
                            onChatDanmaku = component.watchTogetherPreferences::setChatDanmakuEnabled,
                            onChatPreview = component.watchTogetherPreferences::setChatPreviewEnabled,
                        )
                    } else {
                        AccountSettingsScreen(
                            account = component.account,
                            onBack = ::closePage,
                            onOpenSessions = { openPage(ProfilePage.AccountSessions) },
                        )
                    }

                ProfilePage.Appearance ->
                    AppearanceSettingsScreen(
                        onBack = ::closePage,
                        brandSummary =
                            if (splashAnimation) {
                                "${appIcon.label} · ${splashVariant.label} ›"
                            } else {
                                "${appIcon.label} · 开屏已关闭 ›"
                            },
                        backgroundSummary =
                            if (backgroundImage == null) {
                                "未设置 ›"
                            } else {
                                "已设置 · ${(backgroundDim * 100).toInt()}% 遮罩 ›"
                            },
                        startupSummary = "${startupTab.label} ›",
                        reduceTransparency = reduceTransparency,
                        largeText = largeText,
                        reduceMotion = reduceMotion,
                        onBackground = { sheet = Sheet.Background },
                        onBrand = { openPage(ProfilePage.Splash) },
                        onStartupTab = { sheet = Sheet.StartupTab },
                        onReduceTransparency = prefs::setReduceTransparency,
                        onLargeText = prefs::setLargeText,
                        onReduceMotion = prefs::setReduceMotion,
                    )

                ProfilePage.MediaDiscovery ->
                    if (discoveryAvailable) {
                        MediaDiscoverySettingsScreen(
                            repository = component.tgtoMedia,
                            preferences = component.tgtoMediaPreferences,
                            onBack = ::closePage,
                        )
                    } else {
                        AccountSettingsScreen(
                            account = component.account,
                            onBack = ::closePage,
                            onOpenSessions = { openPage(ProfilePage.AccountSessions) },
                        )
                    }

                ProfilePage.DataAndDiagnostics ->
                    DataAndDiagnosticsScreen(
                        onBack = ::closePage,
                        serverCount = state.servers.size,
                        customUserAgent = customUserAgent,
                        onExport = component::exportServers,
                        onImport = component::importServers,
                        onExportRelay = component::exportRelayServers,
                        onInspectRelay = component::inspectRelayServers,
                        onIsRelay = component::isRelayServers,
                        onImportRelay = component::importRelayServers,
                        videoCacheUsageBytes = videoCacheUsageBytes,
                        videoCacheSize = videoCacheSize,
                        onUserAgent = { sheet = Sheet.UserAgent },
                        onClearCache = { confirmClearCache = true },
                        onClearVideoCache = { confirmClearVideoCache = true },
                    )

                ProfilePage.Downloads ->
                    DownloadsScreen(
                        onBack = ::closePage,
                        manager = component.offlineMedia,
                        onPlay = { offlineToPlay = it },
                    )

                ProfilePage.Splash ->
                    BrandAndSplashScreen(
                        onBack = ::closePage,
                        prefs = prefs,
                        appIcon = appIcon,
                        onAppIcon = { chosen ->
                            setAppIconVariant(chosen)
                            appIcon = chosen
                        },
                    )

                ProfilePage.Root ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().statusBarsPadding(),
                        state = mainListState,
                        contentPadding = PaddingValues(top = Dimens.contentTop, bottom = rootBottomContentInset),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        item(key = "settings-search") {
                            YfFormField(
                                value = settingsQuery,
                                onValueChange = { settingsQuery = it.take(60) },
                                label = "搜索设置",
                                modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
                            )
                        }
                        if (settingsQuery.isNotBlank()) {
                            item(key = "settings-search-results") {
                                SettingsSearchResults(
                                    query = settingsQuery,
                                    includeMediaDiscovery = discoveryAvailable,
                                    onOpen = ::openPage,
                                    onOpenServers = component.onOpenServers,
                                )
                            }
                        }
                        item {
                            Section(title = "服务器与账号") {
                                SettingsCard {
                                    SettingRow(
                                        icon = AppIcons.User,
                                        iconTint = SettingTint.account,
                                        title = "账号与同步",
                                        value =
                                            when (val account = accountState) {
                                                AccountState.Restoring -> "正在恢复 ›"
                                                is AccountState.RestoreFailed -> "连接失败 · 点此重试 ›"
                                                AccountState.SignedOut -> "未登录 ›"
                                                is AccountState.SignedIn -> "${account.session.user.nickname} · 加密同步 ›"
                                            },
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Account) },
                                    )
                                    SettingsDivider()
                                    SettingRow(
                                        icon = AppIcons.Server,
                                        iconTint = SettingTint.servers,
                                        title = "服务器",
                                        value =
                                            if (state.servers.isEmpty()) {
                                                "尚未连接 ›"
                                            } else {
                                                val current = state.currentServer?.serverName
                                                "${state.servers.size} 台 · ${current ?: "未选择"} ›"
                                            },
                                        embedded = true,
                                        onClick = component.onOpenServers,
                                    )
                                }
                            }
                        }

                        item {
                            Section(title = "外观与主题") {
                                SettingsCard {
                                    SettingSegmentRow(
                                        title = "主题",
                                        options = ThemeModeDisplayOrder.map { it.label },
                                        selectedIndex = ThemeModeDisplayOrder.indexOf(mode).coerceAtLeast(0),
                                        onSelect = { prefs.setMode(ThemeModeDisplayOrder[it]) },
                                        icon = AppIcons.Cloud,
                                        iconTint = SettingTint.appearance,
                                    )
                                    SettingsDivider()
                                    SettingSegmentRow(
                                        title = "视觉效果",
                                        options = GlassStyle.entries.map { it.label },
                                        selectedIndex = GlassStyle.entries.indexOf(glassStyle),
                                        onSelect = { prefs.setGlassStyle(GlassStyle.entries[it]) },
                                        icon = AppIcons.Grid,
                                        iconTint = SettingTint.components,
                                    )
                                    SettingsDivider()
                                    SettingRow(
                                        "更多外观与辅助",
                                        "背景 · 启动 · 辅助功能 ›",
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Appearance) },
                                        icon = AppIcons.Info,
                                        iconTint = SettingTint.appearance,
                                    )
                                }
                            }
                        }

                        item {
                            Section(title = "播放") {
                                SettingsCard {
                                    SettingRow(
                                        "播放设置",
                                        "${playbackSettingsSummary(optimizationMode, decoder)} ›",
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Playback) },
                                        icon = AppIcons.Play,
                                        iconTint = SettingTint.playback,
                                    )
                                    SettingsDivider()
                                    SettingRow(
                                        "一起看",
                                        when {
                                            !watchAvailable -> "登录后使用 ›"
                                            watchState.connected ->
                                                "房间 ${watchState.roomCode.orEmpty()} ›"
                                            else -> "$watchNickname ›"
                                        },
                                        embedded = true,
                                        onClick = {
                                            openPage(
                                                if (watchAvailable) {
                                                    ProfilePage.WatchTogether
                                                } else {
                                                    ProfilePage.Account
                                                },
                                            )
                                        },
                                        icon = AppIcons.Chat,
                                        iconTint = SettingTint.watchTogether,
                                    )
                                }
                            }
                        }

                        item {
                            Section(title = "字幕与弹幕") {
                                SettingsCard {
                                    SettingRow(
                                        "弹幕设置",
                                        when (danmakuSources.size) {
                                            0 -> "来源 · 关键词屏蔽 · 显示 ›"
                                            1 -> "1 个来源 · 关键词屏蔽 ›"
                                            else -> "${danmakuSources.size} 个来源 · 关键词屏蔽 ›"
                                        },
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Danmaku) },
                                        icon = AppIcons.Danmaku,
                                        iconTint = SettingTint.danmaku,
                                    )
                                }
                            }
                        }

                        item {
                            Section(title = "下载") {
                                SettingsCard {
                                    DownloadRow(
                                        value = "${offlineItems.size} 项 ›",
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Downloads) },
                                    )
                                }
                            }
                        }

                        item {
                            Section(title = "同步与数据") {
                                SettingsCard {
                                    if (discoveryAvailable) {
                                        SettingRow(
                                            "影视发现",
                                            "榜单 · 追剧日历 · 123 转存 ›",
                                            embedded = true,
                                            onClick = { openPage(ProfilePage.MediaDiscovery) },
                                            icon = AppIcons.Cloud,
                                            iconTint = SettingTint.sync,
                                        )
                                        SettingsDivider()
                                    }
                                    SettingRow(
                                        "高级设置",
                                        "网络兼容 · 备份 · 缓存 · 诊断 ›",
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.DataAndDiagnostics) },
                                        icon = AppIcons.Server,
                                        iconTint = SettingTint.advanced,
                                    )
                                }
                            }
                        }

                        item {
                            Section(title = "关于") {
                                AppUpdateTools()
                                AppVersionFooter()
                            }
                        }
                    }
            }
        }

        offlineToPlay?.takeIf { it.playable }?.let { offline ->
            val path = offline.localPath ?: return@let
            PlayerLauncher(
                items =
                    listOf(
                        PlayerMediaItem(
                            id = offline.itemId,
                            url = offlinePlaybackUri(path),
                            transcodeUrl = offlinePlaybackUri(path),
                            title = offline.title,
                            serverId = offline.serverId,
                            externalSubtitleUri = offline.subtitlePath?.let(::offlinePlaybackUri),
                            externalSubtitleLanguage = offline.subtitleLanguage,
                        ),
                    ),
                startIndex = 0,
                startPositionMs = 0L,
                onLaunched = { offlineToPlay = null },
            )
        }

        when (sheet) {
            Sheet.Background ->
                BackgroundImageSheet(
                    current = backgroundImage,
                    dim = backgroundDim,
                    onPick = prefs::setBackgroundImage,
                    onDim = prefs::setBackgroundDim,
                    onDismiss = { sheet = null },
                )

            Sheet.StartupTab ->
                OptionSheet(
                    title = "启动进入",
                    subtitle = "下次冷启动时打开的页面",
                    options = StartupTab.entries.map { it.label to (it == startupTab) },
                    descriptions = StartupTab.entries.map { it.description },
                    onSelect = { index ->
                        prefs.setStartupTab(StartupTab.entries[index])
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.PlaybackMode ->
                OptionSheet(
                    title = "播放模式",
                    subtitle = "自动模式会根据片源和设备选择合适的播放方式",
                    options =
                        simplePlaybackModes.map {
                            it.simplePlaybackLabel() to (it == optimizationMode)
                        },
                    descriptions =
                        simplePlaybackModes.map {
                            it.playbackOptionCopy().description
                        },
                    onSelect = { index ->
                        component.playbackPreferences.setOptimizationMode(
                            simplePlaybackModes[index],
                        )
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.MediaVersionPreference ->
                OptionSheet(
                    title = "视频版本偏好",
                    subtitle = "同一集有多个文件时按此顺序自动选择，不受入库顺序影响",
                    options =
                        MediaVersionPreference.entries.map {
                            it.playbackOptionCopy().label to (it == mediaVersionPreference)
                        },
                    descriptions = MediaVersionPreference.entries.map { it.playbackOptionCopy().description },
                    onSelect = { index ->
                        component.playbackPreferences.setMediaVersionPreference(
                            MediaVersionPreference.entries[index],
                        )
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.AdvancedPlaybackMode ->
                OptionSheet(
                    title = "YCore 播放策略",
                    subtitle = "高级策略会同时影响内核选择、稳定性、画质与功耗",
                    options =
                        PlaybackOptimizationMode.entries.map {
                            it.playbackOptionCopy().label to (it == optimizationMode)
                        },
                    descriptions = PlaybackOptimizationMode.entries.map { it.playbackOptionCopy().description },
                    onSelect = { index ->
                        component.playbackPreferences.setOptimizationMode(
                            PlaybackOptimizationMode.entries[index],
                        )
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.Engine ->
                OptionSheet(
                    title = "高级内核选择",
                    subtitle = "普通使用建议保持自动选择，仅在兼容问题时锁定",
                    options =
                        PlaybackEngineSelection.selectable.map {
                            it.playbackOptionCopy().label to (it == engineSelection)
                        },
                    descriptions = PlaybackEngineSelection.selectable.map { it.playbackOptionCopy().description },
                    onSelect = { index ->
                        val selection = PlaybackEngineSelection.selectable[index]
                        component.playbackPreferences.setEngineSelection(selection)
                        selection.lockedEngine?.let(prefs::setEngine)
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.Decoder ->
                OptionSheet(
                    title = "解码方式",
                    subtitle = "解码选择会同时影响兼容性、性能与耗电",
                    options = DecoderMode.entries.map { it.playbackOptionCopy().label to (it == decoder) },
                    descriptions = DecoderMode.entries.map { it.playbackOptionCopy().description },
                    onSelect = { index ->
                        prefs.setDecoder(DecoderMode.entries[index])
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.YCoreBufferDuration ->
                OptionSheet(
                    title = "YCore 缓冲时长",
                    subtitle = "自动会按码率和网络动态调整；更长缓冲更抗抖动，但启动和拖动后等待可能增加",
                    options =
                        YCoreBufferDuration.entries.map {
                            it.label to (it == yCoreBufferDuration)
                        },
                    onSelect = { index ->
                        component.playbackPreferences.setYCoreBufferDuration(
                            YCoreBufferDuration.entries[index],
                        )
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.VideoCache ->
                OptionSheet(
                    title = "视频缓存大小",
                    subtitle = "缓存已播放的数据，减少回看与网络抖动造成的卡顿",
                    options = VideoCacheSize.entries.map { it.label to (it == videoCacheSize) },
                    onSelect = { index ->
                        component.playbackPreferences.setVideoCacheSize(VideoCacheSize.entries[index])
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.DanmakuSource ->
                DanmakuSourceDialog(
                    sources = danmakuSources,
                    activeSourceId = danmakuActiveSourceId,
                    onSelect = { component.danmakuPreferences.selectSource(it) },
                    onAdd = { name, url -> component.danmakuPreferences.addSource(name, url) },
                    onUpdate = component.danmakuPreferences::updateSource,
                    onRemove = component.danmakuPreferences::removeSource,
                    onDismiss = { sheet = null },
                )

            Sheet.DanmakuBlocked ->
                DanmakuBlockedDialog(
                    words = danmakuBlocked,
                    onAdd = component.danmakuPreferences::addBlockedWord,
                    onRemove = component.danmakuPreferences::removeBlockedWord,
                    onDismiss = { sheet = null },
                )

            Sheet.SkipSegments ->
                SkipSegmentDialog(
                    skipMode = skipMode,
                    onSelectSkipMode = component.skipSegmentPreferences::setSkipMode,
                    onDismiss = { sheet = null },
                )

            Sheet.UserAgent ->
                UserAgentDialog(
                    current = customUserAgent,
                    onSave = {
                        component.userAgentPreferences.setUserAgent(it)
                        sheet = null
                    },
                    onClear = {
                        component.userAgentPreferences.setUserAgent("")
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.WatchTogether ->
                WatchJoinDialog(
                    connected = watchState.connected,
                    connecting = watchState.connecting,
                    roomCode = watchState.roomCode,
                    participantCount = watchState.participantCount,
                    error = watchState.error ?: watchState.syncWarning,
                    onJoin = { code -> watchTogether.joinRoom(watchEndpoint, code, mediaKey = "") },
                    onEnter = component.onEnterWatchRoom,
                    onLeave = {
                        watchTogether.leave()
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.WatchProfile ->
                WatchProfileDialog(
                    currentName = watchNickname,
                    currentAvatarId = watchAvatarId,
                    onSave = { name, avatarId ->
                        component.watchTogetherPreferences.setProfile(name, avatarId)
                        watchTogether.updateProfile(
                            component.watchTogetherPreferences.nickname.value,
                            component.watchTogetherPreferences.avatarId.value,
                        )
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            null -> Unit
        }

        if (confirmClearCache) {
            ConfirmDialog(
                title = "清除图片缓存",
                message = "将清除图片缓存，下次浏览时重新下载。离线下载的影片不受影响。",
                confirmLabel = "清除",
                destructive = true,
                onConfirm = {
                    confirmClearCache = false
                    screenScope.launch { component.onClearCache() }
                },
                onDismiss = { confirmClearCache = false },
            )
        }

        if (confirmClearVideoCache) {
            ConfirmDialog(
                title = "清除视频缓存",
                message = "将清除已播放视频的临时缓存；不会删除离线下载。正在播放的数据可能会重新从服务器读取。",
                confirmLabel = "清除",
                destructive = true,
                onConfirm = {
                    confirmClearVideoCache = false
                    screenScope.launch {
                        component.onClearVideoCache()
                        videoCacheUsageBytes = component.videoCacheUsageBytes()
                    }
                },
                onDismiss = { confirmClearVideoCache = false },
            )
        }
    }
}

@Composable
private fun DataAndDiagnosticsScreen(
    onBack: () -> Unit,
    serverCount: Int,
    customUserAgent: String,
    onExport: (CharArray, Long) -> Result<String>,
    onImport: (String, CharArray, Long) -> Result<Int>,
    onExportRelay: (Long) -> Result<com.yfuse.core.security.RelayMigrationPackage>,
    onInspectRelay: (String) -> com.yfuse.core.security.RelayMigrationDescriptor,
    onIsRelay: (String) -> Boolean,
    onImportRelay: (String, ByteArray, Long) -> Result<Int>,
    videoCacheUsageBytes: Long?,
    videoCacheSize: VideoCacheSize,
    onUserAgent: () -> Unit,
    onClearCache: () -> Unit,
    onClearVideoCache: () -> Unit,
) {
    SettingsPage(
        title = "高级设置",
        subtitle = "网络兼容、迁移与问题排查",
        onBack = onBack,
    ) {
        item {
            Section(title = "网络与兼容") {
                SettingsCard {
                    SettingRow(
                        "自定义 User-Agent",
                        if (customUserAgent.isBlank()) "应用默认 ›" else "已启用 ›",
                        true,
                        onUserAgent,
                    )
                }
            }
        }
        item {
            Box(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
                ServerBackupTools(
                    serverCount = serverCount,
                    onExport = onExport,
                    onImport = onImport,
                    onExportRelay = onExportRelay,
                    onInspectRelay = onInspectRelay,
                    onIsRelay = onIsRelay,
                    onImportRelay = onImportRelay,
                )
            }
        }
        item {
            Section(title = "缓存") {
                SettingsCard {
                    SettingRow("清除图片缓存", "不影响离线下载 ›", true, onClearCache)
                    SettingsDivider()
                    SettingRow(
                        "清除视频缓存",
                        videoCacheUsageSummary(videoCacheUsageBytes, videoCacheSize),
                        true,
                        onClearVideoCache,
                    )
                }
            }
        }
        item {
            Section(title = "问题诊断") { DiagnosticLogTools() }
        }
    }
}

internal fun videoCacheUsageSummary(
    usedBytes: Long?,
    cacheSize: VideoCacheSize,
): String =
    when {
        usedBytes == null -> "正在计算 · 上限 ${cacheSize.label} ›"
        cacheSize.bytes <= 0L && usedBytes <= 0L -> "已关闭 · 无缓存 ›"
        cacheSize.bytes <= 0L -> "已关闭 · 已用 ${formatDownloadBytes(usedBytes)} ›"
        else -> "已用 ${formatDownloadBytes(usedBytes)} / ${cacheSize.label} ›"
    }

@Composable
internal fun SettingsPage(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val bottomContentInset = systemNavigationContentInset()
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = SettingsHeaderTop, bottom = bottomContentInset),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { SettingsPageHeader(title = title, subtitle = subtitle, onBack = onBack) }
        content()
    }
}

@Composable
private fun SettingsPageHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = SettingsBackInset, end = Dimens.pageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsBackButton(onBack)
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, style = AppTypography.section.strong, color = palette.text)
            subtitle?.let { Text(it, style = AppTypography.caption.regular, color = palette.sub2) }
        }
    }
}

@Composable
internal fun SettingsBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Box(
        modifier
            .pressable(onClickLabel = "返回", onClick = onBack)
            .touchTarget()
            .size(44.dp)
            .liquidGlass(
                shape = AppShapes.control,
                fill = palette.card3,
                border = palette.border,
                over = palette.background,
                sheen = 0.62f,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.ChevronLeft, "返回", tint = palette.text, modifier = Modifier.size(22.dp))
    }
}

internal val SettingsBackInset = 6.dp
internal val SettingsHeaderTop = 8.dp

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .flatGlass(GlassShapes.card, palette.card2, palette.border),
        content = content,
    )
}

@Composable
internal fun Section(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = AppTypography.body.strong.copy(letterSpacing = 0.5.sp),
                color = palette.sub2,
            )
            if (action != null) {
                Text(
                    action,
                    style = AppTypography.caption.strong,
                    color = accent.accent,
                    modifier =
                        Modifier
                            .pressable(onClick = onAction)
                            .touchTarget()
                            .liquidGlass(
                                shape = GlassShapes.chip,
                                fill = palette.card2,
                                border = palette.border,
                                over = palette.background,
                                sheen = 0.52f,
                            ).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        content()
    }
}

@Composable
private fun SettingsSearchResults(
    query: String,
    includeMediaDiscovery: Boolean,
    onOpen: (ProfilePage) -> Unit,
    onOpenServers: () -> Unit,
) {
    val palette = LocalPalette.current
    val needle = query.trim().lowercase()
    val results =
        SettingsSearchDestinations
            .filter { includeMediaDiscovery || it.page != ProfilePage.MediaDiscovery }
            .filter { destination ->
                listOf(destination.title, destination.summary, destination.keywords)
                    .any { needle in it.lowercase() }
            }
    Section(title = "搜索结果") {
        SettingsCard {
            if (results.isEmpty()) {
                Text(
                    "没有匹配的设置项",
                    style = AppTypography.body.regular,
                    color = palette.sub2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                )
            } else {
                results.forEachIndexed { index, destination ->
                    SettingRow(
                        title = destination.title,
                        value = "${destination.summary} ›",
                        embedded = true,
                        onClick = {
                            if (destination.opensServers) {
                                onOpenServers()
                            } else {
                                destination.page?.let(onOpen)
                            }
                        },
                        icon = destination.icon,
                        iconTint = destination.tint,
                    )
                    if (index < results.lastIndex) SettingsDivider()
                }
            }
        }
    }
}

@Composable
private fun SettingIconTile(
    icon: ImageVector,
    tint: Color,
) {
    Box(
        Modifier
            .size(28.dp)
            .clip(AppShapes.thumb)
            .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.16f), tint))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

@Composable
internal fun SettingRow(
    title: String,
    value: String,
    embedded: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
) {
    val palette = LocalPalette.current
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val rowModifier =
        Modifier
            .fillMaxWidth()
            .let { if (embedded) it else it.glass(AppShapes.control, palette.card2, palette.border) }
            .let { if (onClick != null) it.pressable(onClick = onClick) else it }
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    BoxWithConstraints(rowModifier) {
        val stacked = largeText || windowWidthTier(maxWidth) == WindowWidthTier.Compact
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) SettingIconTile(icon, iconTint)
            if (stacked) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = AppTypography.body.medium, color = palette.text, maxLines = 2)
                    Text(value, style = AppTypography.body.regular, color = palette.sub2, maxLines = 2)
                }
            } else {
                Text(
                    title,
                    style = AppTypography.body.medium,
                    color = palette.text,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    value,
                    style = AppTypography.body.regular,
                    color = palette.sub2,
                    maxLines = 2,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    value: String,
    embedded: Boolean = false,
    onClick: () -> Unit,
) {
    SettingRow(
        title = "下载与离线库",
        value = value,
        embedded = embedded,
        onClick = onClick,
        icon = AppIcons.Download,
        iconTint = SettingTint.downloads,
    )
}

@Composable
private fun BrandAndSplashScreen(
    onBack: () -> Unit,
    prefs: ThemePreferences,
    appIcon: AppIconVariant,
    onAppIcon: (AppIconVariant) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val enabled by prefs.splashAnimation.collectAsState()
    val selected by prefs.splashVariant.collectAsState()

    LaunchedEffect(Unit) {
        val stored = prefs.splashVariant.value
        if (stored.mark != appIcon.splashMark) prefs.setSplashVariant(appIcon.splashMark.defaultAnimation)
    }

    fun selectIcon(variant: AppIconVariant) {
        onAppIcon(variant)
        if (selected.mark != variant.splashMark) prefs.setSplashVariant(variant.splashMark.defaultAnimation)
    }

    fun selectAnimation(variant: SplashAnimation) {
        prefs.setSplashVariant(variant)
        val icon = variant.mark.appIconFor(appIcon)
        if (icon != appIcon) onAppIcon(icon)
    }

    SettingsPage(
        title = "Logo 与开屏动画",
        subtitle = "更换 Logo 会带上它自己的开屏；启动器可能需要几秒刷新",
        onBack = onBack,
    ) {
        item {
            Section(title = "APP 图标") {
                SettingsCard {
                    AppIconVariant.entries.forEachIndexed { index, variant ->
                        if (index > 0) SettingsDivider()
                        AppIconRow(variant = variant, selected = variant == appIcon, onClick = { selectIcon(variant) })
                    }
                }
            }
        }
        item {
            Section(title = "开屏动画") {
                SettingsCard { SwitchRow("启动时播放", enabled, true) { prefs.setSplashAnimation(it) } }
            }
        }
        if (enabled) {
            items(SplashAnimation.entries) { variant ->
                val active = variant == selected
                Column(
                    Modifier
                        .padding(horizontal = Dimens.pageHorizontal)
                        .fillMaxWidth()
                        .pressable(role = Role.RadioButton) { selectAnimation(variant) }
                        .semantics { this.selected = active }
                        .liquidGlass(
                            shape = AppShapes.card,
                            fill = palette.card2,
                            border = palette.border,
                            over = palette.background,
                            sheen = 0.54f,
                        ).then(
                            if (active) {
                                Modifier.border(2.dp, accent.border, AppShapes.card)
                            } else {
                                Modifier
                            },
                        ).padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SplashPreview(
                        variant = variant,
                        playing = true,
                        modifier = Modifier.fillMaxWidth(0.72f).aspectRatio(1f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            variant.label,
                            style = if (active) AppTypography.body.strong else AppTypography.body.medium,
                            color = if (active) accent.accent else palette.text,
                        )
                        if (active) {
                            Icon(
                                AppIcons.Check,
                                null,
                                tint = accent.accent,
                                modifier = Modifier.padding(start = 6.dp).size(15.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        variant.description,
                        style = AppTypography.body.regular,
                        color = palette.sub2,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "配${variant.mark.label}",
                        style = AppTypography.caption.regular,
                        color = palette.hint,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIconRow(
    variant: AppIconVariant,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(
                haptic = HapticSignal.Select,
                role = Role.RadioButton,
                onClickLabel = variant.label,
                onClick = onClick,
            ).semantics { this.selected = selected }
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconPreview(variant, Modifier.size(46.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                variant.label,
                style = if (selected) AppTypography.body.strong else AppTypography.body.medium,
                color = if (selected) accent.accent else palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                variant.description,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(accent.accent), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Check, contentDescription = null, tint = accent.onAccent, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    embedded: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    description: String? = null,
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (embedded) it else it.glass(AppShapes.control, palette.card2, palette.border) }
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = onChange,
            ).heightIn(min = MinTouchTarget)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) SettingIconTile(icon, iconTint)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppTypography.body.medium,
                color = palette.text,
                maxLines = 2,
            )
            description?.takeIf(String::isNotBlank)?.let { copy ->
                Spacer(Modifier.height(3.dp))
                Text(
                    copy,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 3,
                )
            }
        }
        PillSwitch(checked)
    }
}

@Composable
private fun BackgroundImageSheet(
    current: String?,
    dim: Float,
    onPick: (String?) -> Unit,
    onDim: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val pick =
        rememberBackgroundImagePicker { uri ->
            if (uri != null) {
                current?.takeIf { it != uri }?.let(::releaseBackgroundImage)
                onPick(uri)
            }
        }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(title = "背景图", subtitle = "整个应用的背景；正文仍然画在主题自己的底色上", onClose = onDismiss)
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            OverlayOptionRow(
                label = if (current == null) "选择图片" else "更换图片",
                description = if (current == null) "从相册或文件中选择" else current,
                selected = false,
                onClick = pick,
            )
            if (current != null) {
                OverlayOptionRow(
                    label = "移除背景图",
                    description = "回到主题自己的底色",
                    selected = false,
                    destructive = true,
                    onClick = {
                        releaseBackgroundImage(current)
                        onPick(null)
                    },
                )
            }
        }
        if (current != null) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("遮罩", style = AppTypography.caption.strong, color = palette.sub2)
                Text(
                    "${(dim * 100).toInt()}%",
                    style = AppTypography.caption.medium,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
            Spacer(Modifier.height(6.dp))
            Slider(value = dim, onValueChange = onDim, valueRange = 0.3f..1f)
            Text("越低，背景图越清晰；越高，文字越容易读", style = AppTypography.caption.regular, color = palette.sub2)
        }
    }
}

@Composable
internal fun SettingSegmentRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
) {
    val palette = LocalPalette.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val stacked = options.size > 2 && windowWidthTier(maxWidth) == WindowWidthTier.Compact
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) SettingIconTile(icon, iconTint)
                    Text(
                        title,
                        style = AppTypography.body.medium,
                        color = palette.text,
                        maxLines = 2,
                    )
                }
                SettingSegmentControl(
                    options = options,
                    selectedIndex = selectedIndex,
                    expanded = true,
                    onSelect = onSelect,
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) SettingIconTile(icon, iconTint)
                Text(
                    title,
                    style = AppTypography.body.medium,
                    color = palette.text,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                SettingSegmentControl(
                    options = options,
                    selectedIndex = selectedIndex,
                    expanded = false,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun SettingSegmentControl(
    options: List<String>,
    selectedIndex: Int,
    expanded: Boolean,
    onSelect: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        Modifier
            .then(if (expanded) Modifier.fillMaxWidth() else Modifier)
            .selectableGroup()
            .flatGlass(GlassShapes.chip, palette.card3, palette.border)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Box(
                Modifier
                    .then(if (expanded) Modifier.weight(1f) else Modifier)
                    .heightIn(min = 30.dp)
                    .pressable(
                        pressedScale = 0.97f,
                        haptic = HapticSignal.Select,
                        role = Role.RadioButton,
                        focusShape = GlassShapes.chip,
                        onClickLabel = label,
                        onClick = { onSelect(index) },
                    ).semantics { selected = isSelected }
                    .liquidGlass(
                        shape = GlassShapes.chip,
                        fill = if (isSelected) palette.card2 else Color.Transparent,
                        border = if (isSelected) accent.border else Color.Transparent,
                        over = palette.background,
                        sheen = if (isSelected) 0.66f else 0.28f,
                    ).padding(horizontal = if (expanded) 6.dp else 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = if (isSelected) AppTypography.caption.strong else AppTypography.caption.medium,
                    color = if (isSelected) accent.accent else palette.sub2,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun SettingsDivider() {
    val palette = LocalPalette.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(
            palette.border.copy(alpha = if (palette.isDark) 0.24f else 0.48f),
        ),
    )
}

@Composable
private fun DescribedSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = onChange,
            ).heightIn(min = MinTouchTarget)
            .glass(AppShapes.control, palette.card2, palette.border)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.body.medium, color = palette.text, maxLines = 2)
            Spacer(Modifier.height(3.dp))
            Text(description, style = AppTypography.caption.regular, color = palette.sub2, maxLines = 3)
        }
        PillSwitch(checked)
    }
}

@Composable
private fun PillSwitch(checked: Boolean) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = Motion.settle<Float>(reduceMotion),
        label = "switchKnob",
    )
    val track by animateColorAsState(
        targetValue = if (checked) accent.accent else palette.sub2.copy(alpha = if (palette.isDark) 0.30f else 0.28f),
        animationSpec = Motion.settle<Color>(reduceMotion),
        label = "switchTrack",
    )
    Box(
        Modifier
            .width(46.dp)
            .height(28.dp)
            .clip(AppShapes.pill)
            .background(track),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(
                    horizontal = 3.dp,
                ).offset(
                    x = SwitchTravel * progress,
                ).size(22.dp)
                .shadow(GlassLift.control, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

private val SwitchTravel = 18.dp

@Composable
private fun OptionSheet(
    title: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    descriptions: List<String> = emptyList(),
) {
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(title = title, subtitle = subtitle, onClose = onDismiss)
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            options.forEachIndexed { index, (label, selected) ->
                OverlayOptionRow(
                    label = label,
                    selected = selected,
                    description = descriptions.getOrNull(index),
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}
