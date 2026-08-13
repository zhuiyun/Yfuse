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
import com.yfuse.core.data.DanmakuSource
import com.yfuse.core.data.PlaybackRecoverySnapshot
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.VideoCacheSize
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.activeOr
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
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
import com.yfuse.core.designsystem.WindowWidthTier
import com.yfuse.core.designsystem.defaultAnimation
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.windowWidthTier
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.StartupTab
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.SyncMutationKind
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.PlayerMediaItem
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.flatGlass as glass

/** Which option sheet is open — the prototype's `settingsSheetTab`. */
private enum class Sheet {
    // 主题 and 主题色 are answered in place on the 外观 page now — see [SettingSegmentRow]
    // and [AccentPickerRow] — so they no longer have sheets of their own.
    StartupTab,
    Background,
    Engine,
    Decoder,
    DanmakuSource,
    DanmakuBlocked,
    SkipSegments,
    UserAgent,
    WatchTogether,
    WatchProfile,
    WatchEndpoint,
    VideoCache,
    WifiQuality,
    CellularQuality,
}

private enum class ProfilePage {
    Root,
    Account,
    Playback,
    Danmaku,
    WatchTogether,
    Appearance,
    DataAndDiagnostics,
    Downloads,
    Recovery,
    Splash,
}

/** 个人中心 — `padding:52px 18px 100px; gap:18px`. */
@Composable
fun ProfileScreen(component: ProfileComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val prefs = component.themePreferences
    val mode by prefs.mode.collectAsState()
    val accent by prefs.accent.collectAsState()
    val reduceTransparency by prefs.reduceTransparency.collectAsState()
    val largeText by prefs.largeText.collectAsState()
    val reduceMotion by prefs.reduceMotion.collectAsState()
    val engine by prefs.engine.collectAsState()
    val decoder by prefs.decoder.collectAsState()
    val autoNext by prefs.autoNext.collectAsState()
    val splashAnimation by prefs.splashAnimation.collectAsState()
    val splashVariant by prefs.splashVariant.collectAsState()
    val startupTab by prefs.startupTab.collectAsState()
    val glassStyle by prefs.glassStyle.collectAsState()
    val backgroundImage by prefs.backgroundImage.collectAsState()
    val backgroundDim by prefs.backgroundDim.collectAsState()
    // Read from the package manager rather than a preference: the launcher's component state
    // is the truth, and it survives a reinstall of the app's own settings.
    var appIcon by remember { mutableStateOf(currentAppIconVariant()) }
    val videoCacheSize by component.playbackPreferences.videoCacheSize.collectAsState()
    val smartCrossServerSource by
        component.playbackPreferences.smartCrossServerSource.collectAsState()
    val wifiQualityCap by component.playbackPreferences.wifiQualityCap.collectAsState()
    val cellularQualityCap by component.playbackPreferences.cellularQualityCap.collectAsState()
    val autoQualityDowngrade by
        component.playbackPreferences.autoQualityDowngrade.collectAsState()
    val qualityLocked by component.playbackPreferences.qualityLocked.collectAsState()
    val resumePrompt by component.playbackPreferences.resumePrompt.collectAsState()
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
    val recoverySnapshot by component.playbackRecovery.snapshot.collectAsState()
    val syncState by component.syncManager.state.collectAsState()
    val accountState by component.account.state.collectAsState()

    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var pageStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var offlineToPlay by remember { mutableStateOf<OfflineMedia?>(null) }
    var recoveryToPlay by remember {
        mutableStateOf<Pair<PlayerMediaItem, PlaybackRecoverySnapshot>?>(null)
    }
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

    Box(Modifier.fillMaxSize()) {
        val navigationBackStack =
            remember(pageStack) {
                listOf(ProfilePage.Root) + pageStack.map(ProfilePage::valueOf)
            }
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
                    )

                ProfilePage.Playback ->
                    PlaybackSettingsScreen(
                        onBack = ::closePage,
                        engine = engine,
                        decoder = decoder,
                        autoNext = autoNext,
                        smartCrossServerSource = smartCrossServerSource,
                        wifiQualityCap = wifiQualityCap,
                        cellularQualityCap = cellularQualityCap,
                        autoQualityDowngrade = autoQualityDowngrade,
                        qualityLocked = qualityLocked,
                        resumePrompt = resumePrompt,
                        videoCacheSize = videoCacheSize,
                        skipSegments =
                            if (skipTimesBySeries.isEmpty()) {
                                "${skipMode.label} · 跟随服务器 ›"
                            } else {
                                "${skipMode.label} ›"
                            },
                        onEngine = { sheet = Sheet.Engine },
                        onDecoder = { sheet = Sheet.Decoder },
                        onAutoNext = prefs::setAutoNext,
                        onSmartCrossServerSource =
                            component.playbackPreferences::setSmartCrossServerSource,
                        onWifiQuality = { sheet = Sheet.WifiQuality },
                        onCellularQuality = { sheet = Sheet.CellularQuality },
                        onAutoQualityDowngrade =
                            component.playbackPreferences::setAutoQualityDowngrade,
                        onQualityLocked = component.playbackPreferences::setQualityLocked,
                        onResumePrompt = component.playbackPreferences::setResumePrompt,
                        onVideoCache = { sheet = Sheet.VideoCache },
                        onSkipSegments = { sheet = Sheet.SkipSegments },
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
                        blockedSummary =
                            if (danmakuBlocked.isEmpty()) {
                                "未设置 ›"
                            } else {
                                "${danmakuBlocked.size} 个 ›"
                            },
                        onSources = { sheet = Sheet.DanmakuSource },
                        onBlockedWords = { sheet = Sheet.DanmakuBlocked },
                    )

                ProfilePage.WatchTogether ->
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

                ProfilePage.Appearance ->
                    AppearanceSettingsScreen(
                        onBack = ::closePage,
                        mode = mode,
                        accent = accent,
                        glassStyle = glassStyle,
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
                        onThemeMode = prefs::setMode,
                        onAccent = prefs::setAccent,
                        onGlassStyle = prefs::setGlassStyle,
                        onBackground = { sheet = Sheet.Background },
                        onBrand = { openPage(ProfilePage.Splash) },
                        onStartupTab = { sheet = Sheet.StartupTab },
                        onReduceTransparency = prefs::setReduceTransparency,
                        onLargeText = prefs::setLargeText,
                        onReduceMotion = prefs::setReduceMotion,
                    )

                ProfilePage.DataAndDiagnostics -> {
                    DataAndDiagnosticsScreen(
                        onBack = ::closePage,
                        serverCount = state.servers.size,
                        customUserAgent = customUserAgent,
                        watchEndpoint = watchEndpoint,
                        onExport = component::exportServers,
                        onImport = component::importServers,
                        onUserAgent = { sheet = Sheet.UserAgent },
                        onWatchEndpoint = { sheet = Sheet.WatchEndpoint },
                        onClearCache = { confirmClearCache = true },
                    )
                }

                ProfilePage.Downloads ->
                    DownloadsScreen(
                        onBack = ::closePage,
                        manager = component.offlineMedia,
                        onPlay = { offlineToPlay = it },
                    )

                ProfilePage.Recovery,
                ProfilePage.Splash,
                ->
                    ProfileUtilityScreen(
                        page = activePage,
                        onBack = ::closePage,
                        syncManager = component.syncManager,
                        playbackRecovery = component.playbackRecovery,
                        themePreferences = prefs,
                        appIcon = appIcon,
                        onAppIcon = { chosen ->
                            setAppIconVariant(chosen)
                            appIcon = chosen
                        },
                        onResumePlayback = { snapshot ->
                            component.recoveryItem(snapshot)?.let { recoveryToPlay = it to snapshot }
                        },
                    )
                ProfilePage.Root -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().statusBarsPadding(),
                        state = mainListState,
                        contentPadding =
                            PaddingValues(
                                top = Dimens.contentTop,
                                bottom = rootBottomContentInset,
                            ),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        item {
                            Section(title = "Yfuse 账号") {
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
                                                is AccountState.SignedIn ->
                                                    "${account.session.user.nickname} · 手动加密同步 ›"
                                            },
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Account) },
                                    )
                                }
                            }
                        }

                        item {
                            // The list itself lives in the 服务器 tab now. What belongs
                            // here is the one line that says how many there are and
                            // which one is live — the rest of this page reads from it.
                            Section(title = "我的服务器") {
                                SettingsCard {
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
                            Section(title = "设置") {
                                SettingsCard {
                                    SettingRow(
                                        "播放",
                                        "${playbackSettingsSummary(engine, decoder)} ›",
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Playback) },
                                        icon = AppIcons.Play,
                                        iconTint = SettingTint.playback,
                                    )
                                    SettingsDivider()
                                    SettingRow(
                                        "弹幕",
                                        when (danmakuSources.size) {
                                            0 -> "未配置 ›"
                                            1 -> "1 个来源 ›"
                                            else -> "${danmakuSources.size} 个来源 ›"
                                        },
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Danmaku) },
                                        icon = AppIcons.Danmaku,
                                        iconTint = SettingTint.danmaku,
                                    )
                                    SettingsDivider()
                                    SettingRow(
                                        "一起看",
                                        if (watchState.connected) {
                                            "房间 ${watchState.roomCode.orEmpty()} ›"
                                        } else {
                                            "$watchNickname ›"
                                        },
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.WatchTogether) },
                                        icon = AppIcons.Chat,
                                        iconTint = SettingTint.watchTogether,
                                    )
                                    SettingsDivider()
                                    SettingRow(
                                        "外观与辅助",
                                        "${mode.label} · ${accent.label}色 ›",
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Appearance) },
                                        icon = AppIcons.Cloud,
                                        iconTint = SettingTint.appearance,
                                    )
                                }
                            }
                        }

                        item {
                            Section(title = "我的内容") {
                                SettingsCard {
                                    DownloadRow(
                                        value = "${offlineItems.size} 项 ›",
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Downloads) },
                                    )
                                    SettingsDivider()
                                    SettingRow(
                                        icon = AppIcons.Refresh,
                                        iconTint = SettingTint.sync,
                                        title = "播放恢复与同步",
                                        value =
                                            when {
                                                syncState.conflicts.isNotEmpty() ->
                                                    "${syncState.conflicts.size} 个冲突 ›"
                                                syncState.pendingCount > 0 ->
                                                    "${syncState.pendingCount} 项待同步 ›"
                                                recoverySnapshot != null -> "可继续播放 ›"
                                                else -> "状态正常 ›"
                                            },
                                        embedded = true,
                                        onClick = { openPage(ProfilePage.Recovery) },
                                    )
                                    SettingsDivider()
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

                        item { AppUpdateTools() }
                        item { AppVersionFooter() }
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
                            url = "file://$path",
                            transcodeUrl = "file://$path",
                            title = offline.title,
                            serverId = offline.serverId,
                            externalSubtitleUri = offline.subtitlePath?.let { "file://$it" },
                            externalSubtitleLanguage = offline.subtitleLanguage,
                        ),
                    ),
                startIndex = 0,
                startPositionMs = 0L,
                onLaunched = { offlineToPlay = null },
            )
        }

        recoveryToPlay?.let { (item, snapshot) ->
            PlayerLauncher(
                items = listOf(item),
                startIndex = 0,
                startPositionMs = snapshot.positionMs,
                onLaunched = { recoveryToPlay = null },
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

            Sheet.Engine ->
                OptionSheet(
                    title = "播放内核",
                    subtitle = "用于新播放；播放时的临时切换不会改变此默认值",
                    options = PlayerEngine.selectable.map { it.playbackOptionCopy().label to (it == engine) },
                    descriptions = PlayerEngine.selectable.map { it.playbackOptionCopy().description },
                    onSelect = { index ->
                        prefs.setEngine(PlayerEngine.selectable[index])
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

            Sheet.WifiQuality ->
                OptionSheet(
                    title = "Wi-Fi 画质上限",
                    subtitle = "新播放会在保留每服务器偏好的同时遵守此上限",
                    options = PlaybackQuality.entries.map { it.label to (it == wifiQualityCap) },
                    onSelect = { index ->
                        component.playbackPreferences.setWifiQualityCap(PlaybackQuality.entries[index])
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.CellularQuality ->
                OptionSheet(
                    title = "蜂窝网络画质上限",
                    subtitle = "移动网络与其他计费网络使用此上限",
                    options = PlaybackQuality.entries.map { it.label to (it == cellularQualityCap) },
                    onSelect = { index ->
                        component.playbackPreferences.setCellularQualityCap(
                            PlaybackQuality.entries[index],
                        )
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
                    onJoin = { code ->
                        // Joining from here has no media context, so the room is entered without
                        // a mediaKey — the room's own timeline names the title, which the shell
                        // resolves and opens (see App.kt). The dialog deliberately stays up
                        // rather than closing on tap: dismissing it immediately was the whole of
                        // what "加入" appeared to do, whether the join worked, failed, or landed
                        // on something this library doesn't have.
                        watchTogether.joinRoom(watchEndpoint, code, mediaKey = "")
                    },
                    // Same reason the dialog stays up for 加入: a successful entry navigates to
                    // another tab and takes this screen with it, and a failed one has a message
                    // to show that needs somewhere to appear.
                    onEnter = component.onEnterWatchRoom,
                    onLeave = {
                        watchTogether.leave()
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )

            Sheet.WatchEndpoint ->
                WatchEndpointDialog(
                    current = watchEndpoint,
                    onSave = { endpoint, localCleartextConfirmed ->
                        val result =
                            component.watchTogetherPreferences.setEndpoint(
                                endpoint,
                                localCleartextConfirmed,
                            )
                        if (result.allowed) sheet = null
                    },
                    onReset = {
                        component.watchTogetherPreferences.setEndpoint(
                            WatchTogetherPreferences.DEFAULT_ENDPOINT,
                        )
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
                message =
                    "将清除图片缓存，下次浏览时重新下载。" +
                        "离线下载的影片不受影响。",
                confirmLabel = "清除",
                destructive = true,
                onConfirm = {
                    confirmClearCache = false
                    screenScope.launch { component.onClearCache() }
                },
                onDismiss = { confirmClearCache = false },
            )
        }
    }
}

@Composable
private fun DataAndDiagnosticsScreen(
    onBack: () -> Unit,
    serverCount: Int,
    customUserAgent: String,
    watchEndpoint: String,
    onExport: (CharArray, Long) -> Result<String>,
    onImport: (String, CharArray, Long) -> Result<Int>,
    onUserAgent: () -> Unit,
    onWatchEndpoint: () -> Unit,
    onClearCache: () -> Unit,
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
                    SettingsDivider()
                    SettingRow(
                        "一起看服务地址",
                        if (watchEndpoint.trimEnd('/') ==
                            WatchTogetherPreferences.DEFAULT_ENDPOINT.trimEnd('/')
                        ) {
                            "默认 ›"
                        } else {
                            "自定义 ›"
                        },
                        true,
                        onWatchEndpoint,
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
                )
            }
        }
        item {
            Section(title = "缓存") {
                SettingsCard {
                    SettingRow(
                        "清除图片缓存",
                        "不影响离线下载 ›",
                        true,
                        onClearCache,
                    )
                }
            }
        }
        item {
            Section(title = "问题诊断") {
                DiagnosticLogTools()
            }
        }
    }
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
        item {
            SettingsPageHeader(title = title, subtitle = subtitle, onBack = onBack)
        }
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
            subtitle?.let {
                Text(it, style = AppTypography.caption.regular, color = palette.sub2)
            }
        }
    }
}

/**
 * 返回 on a second-level page.
 *
 * It was a 34dp square inset 18dp from the edge and 20dp below the status bar — the smallest
 * visible control in the app, sitting where nothing else is, in the corner a thumb reaches
 * least comfortably on a tall phone. The hit area was already 48dp via [touchTarget]; what
 * was missing was something to aim at. 44dp of glass, pushed into the corner it belongs in.
 */
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
            .glass(AppShapes.control, palette.card3, palette.border),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.ChevronLeft,
            "返回",
            tint = palette.text,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** How far a second-level page's 返回 key sits from the left edge. */
internal val SettingsBackInset = 6.dp

/** …and from the status bar. Content below keeps [Dimens.contentTop]'s breathing room. */
internal val SettingsHeaderTop = 8.dp

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card, palette.card2, palette.border)
            .clip(GlassShapes.card),
        content = content,
    )
}

/**
 * Section header — `700 12px`, `--pg-sub2`, `letter-spacing:.5px`, `margin-bottom:8px`;
 * optional trailing action at `600 11px Manrope`, `#3D64C9`.
 */
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
                            .glass(
                                shape = GlassShapes.chip,
                                fill = palette.card2,
                                border = palette.border,
                            ).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        content()
    }
}

/**
 * The glyph tile at the head of a settings row.
 *
 * A settings page is a list of names in one weight and one colour, and the eye has nothing to
 * navigate by — you read every line to find the one you came for. A saturated glyph per row
 * gives each entry a fixed landmark, which is why every phone settings app has them; the
 * colour is per row and stable, so 播放 is always the same green and is found by that before
 * it is read.
 */
@Composable
private fun SettingIconTile(
    icon: ImageVector,
    tint: Color,
) {
    Box(
        Modifier
            .size(28.dp)
            .clip(AppShapes.thumb)
            .background(
                Brush.linearGradient(
                    listOf(lerp(tint, Color.White, 0.16f), tint),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** Settings row — `--pg-card2`, `padding:13px 16px`, `500 13px` / `400 12px Manrope`. */
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
            .let {
                if (embedded) {
                    it
                } else {
                    it.glass(AppShapes.control, palette.card2, palette.border)
                }
            }.let { if (onClick != null) it.pressable(onClick = onClick) else it }
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
            // Under large text the name and its value stop fitting on one line, so they
            // stack — but beside the tile rather than under it, which keeps the glyph
            // aligned with the row it belongs to instead of floating above a block.
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

/**
 * Second-level page for the logo and the launch that assembles it.
 *
 * These used to be two entries — an APP 图标 sheet and an 开屏动画 page — and nothing joined
 * them, so the app could sit on the home screen as a cloud and then unfold a water-fire
 * ribbon on launch. They are one page and one decision now: each mark owns its own pair of
 * choreographies, and choosing either end moves the other (see [SplashMark.appIconFor]).
 *
 * Every animation card runs the real choreography on a loop rather than showing a still: the
 * whole difference between the variants is in the motion, so a static thumbnail would say
 * nothing. They are stacked full width rather than sat side by side because the squash is
 * what you are here to judge, and it does not read at thumbnail size.
 */
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

    // A pair that drifted apart — an upgrade from a build that mapped logo and launch
    // differently — is repaired once, on entry, so what is ticked here is also what the next
    // launch plays. The splash resolves the same way; see AnimatedSplashApp.
    //
    // On entry and not on every change: the selections below write both halves in the same
    // click, and a repair that re-ran on either of them would race the state that has not
    // recomposed yet and pull a chosen 水漾成键 back to 水滴入云.
    LaunchedEffect(Unit) {
        val stored = prefs.splashVariant.value
        if (stored.mark != appIcon.splashMark) {
            prefs.setSplashVariant(appIcon.splashMark.defaultAnimation)
        }
    }

    // Selecting a logo keeps the animation if it already belongs to that mark, so switching
    // 当前 Logo → 石墨 does not throw away a chosen 水火交接.
    fun selectIcon(variant: AppIconVariant) {
        onAppIcon(variant)
        if (selected.mark != variant.splashMark) {
            prefs.setSplashVariant(variant.splashMark.defaultAnimation)
        }
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
                        AppIconRow(
                            variant = variant,
                            selected = variant == appIcon,
                            onClick = { selectIcon(variant) },
                        )
                    }
                }
            }
        }

        item {
            Section(title = "开屏动画") {
                SettingsCard {
                    SwitchRow("启动时播放", enabled, true) { prefs.setSplashAnimation(it) }
                }
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
                        .clip(AppShapes.card)
                        .background(palette.card2)
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) accent.border else palette.border,
                            shape = AppShapes.card,
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
                    // Which logo this launch belongs to, on the card itself: picking it also
                    // changes the icon on the home screen, and that should not be a surprise.
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

/** One launcher icon, its artwork on its own ground beside the name. */
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
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(accent.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = accent.onAccent,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/** Same row with the prototype's 38×22 pill switch. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    embedded: Boolean = false,
    // Ahead of [onChange] so a trailing-lambda call still binds the lambda to it; as the
    // last parameters these captured `{ … }` and asked for a Color instead.
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                if (embedded) {
                    it
                } else {
                    it.glass(AppShapes.control, palette.card2, palette.border)
                }
            }.toggleable(
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
        Text(
            title,
            style = AppTypography.body.medium,
            color = palette.text,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        PillSwitch(checked)
    }
}

/**
 * 背景图 — pick a picture, decide how much of it shows through, or take it away again.
 *
 * The dim slider is not decoration: every text colour in the app was chosen against a flat
 * palette, so a photograph behind the page is a contrast decision. Rather than guessing a
 * safe strength or refusing dark pictures, the control that creates the problem is put next
 * to the one that solves it, and the page behind the sheet updates as it moves.
 */
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
                // Release the previous grant before adopting the new one, or the app accumulates
                // read access to every photo the user has ever tried.
                current?.takeIf { it != uri }?.let(::releaseBackgroundImage)
                onPick(uri)
            }
        }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "背景图",
            subtitle = "整个应用的背景；正文仍然画在主题自己的底色上",
            onClose = onDismiss,
        )
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
            Slider(
                value = dim,
                onValueChange = onDim,
                // Below about a third the page's own surfaces stop carrying the copy, and the
                // app becomes a photograph with text on it.
                valueRange = 0.3f..1f,
            )
            Text(
                "越低，背景图越清晰；越高，文字越容易读",
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
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
    val accent = LocalAccentColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
        Row(
            Modifier
                .selectableGroup()
                .clip(GlassShapes.chip)
                .background(palette.card3)
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .heightIn(min = 30.dp)
                        .clip(GlassShapes.chip)
                        // The selected cell is filled with the accent itself rather than the
                        // 8% container it used to carry. This is the control that answers 主题
                        // and 视觉效果, and it sits two rows under the swatches — with a fill
                        // that pale, changing 主题色 repainted nothing the eye could find on
                        // the page where the choice is made, which reads as a dead setting.
                        .background(if (selected) accent.accent else Color.Transparent)
                        .pressable(
                            pressedScale = 0.97f,
                            haptic = HapticSignal.Select,
                            role = Role.RadioButton,
                            focusShape = GlassShapes.chip,
                            onClickLabel = label,
                            onClick = { onSelect(index) },
                        ).semantics { this.selected = selected }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style =
                            if (selected) {
                                AppTypography.caption.strong
                            } else {
                                AppTypography.caption.medium
                            },
                        color = if (selected) accent.onAccent else palette.sub2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The spectrum that stands in for 跟随封面, which has no colour until a page supplies one. */
private val ArtworkSwatchBrush =
    Brush.sweepGradient(
        listOf(
            Color(0xFF3D64C9),
            Color(0xFF8B6FAE),
            Color(0xFFC2564C),
            Color(0xFFC0982F),
            Color(0xFF5F9F6F),
            Color(0xFF3FA89A),
            Color(0xFF3D64C9),
        ),
    )

/**
 * Every accent at once, rather than a list of colour names behind a modal.
 *
 * A colour is chosen by looking at it. The previous row said 「蓝色 ›」 and opened a sheet of
 * six labelled rows — a name is the one description of a colour that cannot be compared with
 * another, and the swatches fit in the space the row already occupied.
 */
@Composable
internal fun AccentPickerRow(
    selected: AccentColor,
    onSelect: (AccentColor) -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIconTile(AppIcons.Star, SettingTint.appearance)
            Text(
                "主题色",
                style = AppTypography.body.medium,
                color = palette.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (selected.followsArtwork) selected.label else "${selected.label}色",
                style = AppTypography.body.regular,
                color = palette.sub2,
                maxLines = 1,
            )
        }
        // Six to a row on a phone; the swatches keep their size and wrap rather than
        // shrinking to fit the whole set across a narrow screen. Six rather than five so
        // 墨 — the "no accent" choice — shares a row instead of hanging alone under two
        // full ones, where it would read as an afterthought rather than an option.
        Column(
            Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AccentColor.entries.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { option ->
                        val isSelected = option == selected
                        Box(
                            Modifier
                                .size(34.dp)
                                .pressable(
                                    haptic = HapticSignal.Select,
                                    role = Role.RadioButton,
                                    onClickLabel =
                                        if (option.followsArtwork) {
                                            option.label
                                        } else {
                                            "${option.label}色"
                                        },
                                    focusShape = CircleShape,
                                    onClick = { onSelect(option) },
                                ).semantics { this.selected = isSelected }
                                .clip(CircleShape)
                                // 跟随封面 has no colour of its own to show, so its swatch is
                                // the whole set at once — a spectrum reads as "it depends"
                                // where any single fill would read as one more hue on offer.
                                .then(
                                    if (option.followsArtwork) {
                                        Modifier.background(ArtworkSwatchBrush)
                                    } else {
                                        Modifier.background(option.color)
                                    },
                                ).then(
                                    if (isSelected) {
                                        Modifier.border(2.dp, palette.text, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsDivider() {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(
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
            Text(
                description,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 3,
            )
        }
        PillSwitch(checked)
    }
}

@Composable
private fun ProfileUtilityScreen(
    page: ProfilePage,
    onBack: () -> Unit,
    syncManager: ServerSyncManager,
    playbackRecovery: PlaybackRecoveryStore,
    themePreferences: ThemePreferences,
    appIcon: AppIconVariant,
    onAppIcon: (AppIconVariant) -> Unit,
    onResumePlayback: (PlaybackRecoverySnapshot) -> Unit,
) {
    if (page == ProfilePage.Splash) {
        BrandAndSplashScreen(
            onBack = onBack,
            prefs = themePreferences,
            appIcon = appIcon,
            onAppIcon = onAppIcon,
        )
        return
    }
    if (page == ProfilePage.Recovery) {
        RecoveryCenterScreen(
            onBack = onBack,
            syncManager = syncManager,
            playbackRecovery = playbackRecovery,
            onResumePlayback = onResumePlayback,
        )
        return
    }
    Unit
}

@Composable
private fun RecoveryCenterScreen(
    onBack: () -> Unit,
    syncManager: ServerSyncManager,
    playbackRecovery: PlaybackRecoveryStore,
    onResumePlayback: (PlaybackRecoverySnapshot) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val sync by syncManager.state.collectAsState()
    val snapshot by playbackRecovery.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val bottomContentInset = systemNavigationContentInset()

    LaunchedEffect(syncManager) {
        syncManager.syncAll()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding =
            PaddingValues(
                top = SettingsHeaderTop,
                bottom = bottomContentInset,
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                // Out past the list's own inset, so 返回 sits in the corner on every
                // second-level page rather than only on the ones built from [SettingsPage].
                Modifier
                    .fillMaxWidth()
                    .offset(x = SettingsBackInset - Dimens.pageHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsBackButton(onBack)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("播放恢复中心", style = AppTypography.section.strong, color = palette.text)
                    Text(
                        "本地断点、服务器同步与冲突处理",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                }
            }
        }

        item {
            RecoverySectionCard("继续播放") {
                val current = snapshot
                if (current == null) {
                    Text(
                        "暂无可恢复的播放记录",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                } else {
                    Text(
                        current.title.ifBlank { "未命名视频" },
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${current.positionMs.asRecoveryClock()} / " +
                            "${current.durationMs.asRecoveryClock()} · ${current.engine}",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecoveryAction("继续播放") { onResumePlayback(current) }
                        RecoveryAction("清除") { playbackRecovery.clear() }
                    }
                }
            }
        }

        item {
            RecoverySectionCard("服务器同步") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${sync.statuses.size} 台服务器 · ${sync.pendingCount} 项待同步",
                        style = AppTypography.caption.medium,
                        color = palette.sub2,
                    )
                    RecoveryAction("立即同步") {
                        scope.launch { syncManager.syncAll(force = true) }
                    }
                }
                if (sync.statuses.isEmpty()) {
                    Text("正在读取服务器状态…", style = AppTypography.caption.regular, color = palette.hint)
                }
                sync.statuses.sortedBy { it.serverName }.forEach { status ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(status.serverName, style = AppTypography.body.strong, color = palette.text)
                            Text(
                                status.error ?: when {
                                    status.syncing -> "同步中…"
                                    status.online == true -> "${status.itemCount} 项 · 已连接"
                                    status.online == false -> "离线"
                                    else -> "等待同步"
                                },
                                style = AppTypography.caption.regular,
                                color = if (status.error != null) palette.error else palette.sub2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            when {
                                status.syncing -> "同步中"
                                status.online == true -> "正常"
                                status.online == false -> "离线"
                                else -> "未知"
                            },
                            style = AppTypography.caption.strong,
                            color = if (status.online == false) palette.error else accent.accent,
                        )
                    }
                }
            }
        }

        if (sync.pendingOperations.isNotEmpty()) {
            item {
                RecoverySectionCard("待同步操作") {
                    sync.pendingOperations.forEach { operation ->
                        Text(
                            "${operation.title} · ${
                                when (operation.kind) {
                                    SyncMutationKind.Favorite -> "收藏"
                                    SyncMutationKind.Played -> "已播放"
                                }
                            } → ${if (operation.desired) "开启" else "关闭"}",
                            style = AppTypography.caption.medium,
                            color = palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (sync.conflicts.isNotEmpty()) {
            item {
                RecoverySectionCard("冲突处理") {
                    sync.conflicts.forEach { conflict ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                conflict.mutation.title,
                                style = AppTypography.body.strong,
                                color = palette.text,
                            )
                            Text(
                                "本地：${if (conflict.mutation.desired) "开启" else "关闭"} · " +
                                    "服务器：${if (conflict.serverValue) "开启" else "关闭"}",
                                style = AppTypography.caption.regular,
                                color = palette.sub2,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RecoveryAction("保留本地") {
                                    scope.launch { syncManager.resolveConflict(conflict, true) }
                                }
                                RecoveryAction("采用服务器") {
                                    scope.launch { syncManager.resolveConflict(conflict, false) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoverySectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card, palette.card, palette.border)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(title, style = AppTypography.body.strong, color = palette.text)
        content()
    }
}

@Composable
private fun RecoveryAction(
    label: String,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Text(
        label,
        style = AppTypography.caption.strong,
        color = accent.accent,
        modifier =
            Modifier
                .pressable(onClick = onClick)
                .touchTarget()
                .glass(GlassShapes.chip, palette.card2, palette.border)
                .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

private fun Long.asRecoveryClock(): String {
    if (this <= 0L) return "--:--"
    val total = this / 1000L
    val minutes = total / 60L
    val seconds = total % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** 38×22 pill switch; the off state keeps a visible track, edge and thumb in both themes. */
@Composable
private fun PillSwitch(checked: Boolean) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // The knob slid nowhere: both states were drawn from scratch with an alignment swap, so
    // flipping a switch was a cut. A switch is the one control whose *movement* is the
    // feedback — the thumb travelling is what says the value changed — so the position is
    // animated and the track colour crossfades with it, on the same spring.
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = Motion.settle<Float>(reduceMotion),
        label = "switchKnob",
    )
    val track by animateColorAsState(
        targetValue =
            if (checked) {
                accent.accent
            } else {
                palette.sub2.copy(alpha = if (palette.isDark) 0.30f else 0.28f)
            },
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
                .padding(horizontal = 3.dp)
                // Travel is the track's inner width less the thumb, so the thumb lands flush
                // against each end instead of being placed by an alignment flip.
                .offset(x = SwitchTravel * progress)
                .size(22.dp)
                // The thumb is the one opaque part: it has to read as a physical object on
                // top of the track in both themes, and a translucent one over the accent
                // fill turned into a pale smudge.
                .shadow(GlassLift.control, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** How far the thumb travels: 46dp track, 3dp of inset each side, a 22dp thumb. */
private val SwitchTravel = 18.dp

/** Single-choice list. Picking a row applies it and closes — there is no confirm step. */
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
        // Spacing, not rules: each option carries its own edge now — see [OverlayOptionRow].
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
