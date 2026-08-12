package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.core.account.AccountState
import com.yfuse.core.data.DanmakuSource
import com.yfuse.core.data.PlaybackRecoverySnapshot
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.data.VideoCacheSize
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.activeOr
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.ReportOverlayVisible
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.SplashPreview
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.serverBadgeColor
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.WindowWidthTier
import com.yfuse.core.designsystem.windowWidthTier
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.StartupTab
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.SyncMutationKind
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.PlayerMediaItem
import kotlinx.coroutines.launch

/** Which option sheet is open — the prototype's `settingsSheetTab`. */
private enum class Sheet {
    ThemeMode,
    StartupTab,
    Accent,
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
    val videoCacheSize by component.playbackPreferences.videoCacheSize.collectAsState()
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
        val navigationBackStack = remember(pageStack) {
            listOf(ProfilePage.Root) + pageStack.map(ProfilePage::valueOf)
        }
        OfficialNavDisplay(
            backStack = navigationBackStack,
            onBack = ::closePage,
            contentKey = ProfilePage::name,
            modifier = Modifier.fillMaxSize(),
        ) { activePage ->
            when (activePage) {
                        ProfilePage.Account -> AccountSettingsScreen(
                            account = component.account,
                            onBack = ::closePage,
                        )

                        ProfilePage.Playback -> PlaybackSettingsScreen(
                            onBack = ::closePage,
                            engine = engine,
                            decoder = decoder,
                            autoNext = autoNext,
                            videoCacheSize = videoCacheSize,
                            skipSegments = if (skipTimesBySeries.isEmpty()) {
                                "${skipMode.label} · 跟随服务器 ›"
                            } else {
                                "${skipMode.label} ›"
                            },
                            onEngine = { sheet = Sheet.Engine },
                            onDecoder = { sheet = Sheet.Decoder },
                            onAutoNext = prefs::setAutoNext,
                            onVideoCache = { sheet = Sheet.VideoCache },
                            onSkipSegments = { sheet = Sheet.SkipSegments },
                        )

                        ProfilePage.Danmaku -> DanmakuSettingsScreen(
                            onBack = ::closePage,
                            sourceSummary = when (danmakuSources.size) {
                                0 -> "未配置 ›"
                                1 -> "${danmakuSources.first().name} ›"
                                else -> {
                                    val active = danmakuSources.activeOr(danmakuActiveSourceId)
                                    "${danmakuSources.size} 个 · ${active?.name.orEmpty()} ›"
                                }
                            },
                            blockedSummary = if (danmakuBlocked.isEmpty()) {
                                "未设置 ›"
                            } else {
                                "${danmakuBlocked.size} 个 ›"
                            },
                            onSources = { sheet = Sheet.DanmakuSource },
                            onBlockedWords = { sheet = Sheet.DanmakuBlocked },
                        )

                        ProfilePage.WatchTogether -> WatchTogetherSettingsScreen(
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

                        ProfilePage.Appearance -> AppearanceSettingsScreen(
                            onBack = ::closePage,
                            mode = mode,
                            accent = accent,
                            splashSummary = if (splashAnimation) "${splashVariant.label} ›" else "已关闭 ›",
                            startupSummary = "${startupTab.label} ›",
                            reduceTransparency = reduceTransparency,
                            largeText = largeText,
                            reduceMotion = reduceMotion,
                            onThemeMode = { sheet = Sheet.ThemeMode },
                            onAccent = { sheet = Sheet.Accent },
                            onSplash = { openPage(ProfilePage.Splash) },
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

                        ProfilePage.Downloads -> DownloadsScreen(
                            onBack = ::closePage,
                            manager = component.offlineMedia,
                            onPlay = { offlineToPlay = it },
                        )

                        ProfilePage.Recovery,
                        ProfilePage.Splash,
                        -> ProfileUtilityScreen(
                            page = activePage,
                            onBack = ::closePage,
                            syncManager = component.syncManager,
                            playbackRecovery = component.playbackRecovery,
                            themePreferences = prefs,
                            onResumePlayback = { snapshot ->
                                component.recoveryItem(snapshot)?.let { recoveryToPlay = it to snapshot }
                            },
                        )
                ProfilePage.Root -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().statusBarsPadding(),
                            state = mainListState,
                            contentPadding = PaddingValues(top = Dimens.contentTop, bottom = TabBarInset),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            item {
                                Section(title = "Yfuse 账号") {
                                    SettingsCard {
                                        SettingRow(
                                            title = "账号与同步",
                                            value = when (val account = accountState) {
                                                AccountState.Restoring -> "正在恢复 ›"
                                                is AccountState.RestoreFailed -> "连接失败 · 点此重试 ›"
                                                AccountState.SignedOut -> "未登录 ›"
                                                is AccountState.SignedIn -> "${account.session.user.nickname} · 手动加密同步 ›"
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
                                            title = "服务器",
                                            value = if (state.servers.isEmpty()) {
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
                                        )
                                        SettingsDivider()
                                        SettingRow(
                                            "外观与辅助",
                                            "${mode.label} · ${accent.label}色 ›",
                                            embedded = true,
                                            onClick = { openPage(ProfilePage.Appearance) },
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
                                            "播放恢复与同步",
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
                items = listOf(
                    PlayerMediaItem(
                        id = offline.itemId,
                        url = "file://$path",
                        transcodeUrl = "file://$path",
                        title = offline.title,
                        serverId = offline.serverId,
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
            Sheet.ThemeMode -> OptionSheet(
                title = "主题模式",
                subtitle = "可跟随系统自动切换深浅外观",
                options = ThemeMode.entries.map { it.label to (it == mode) },
                onSelect = { index ->
                    prefs.setMode(ThemeMode.entries[index])
                    sheet = null
                },
                onDismiss = { sheet = null },
            )

            Sheet.StartupTab -> OptionSheet(
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

            Sheet.Accent -> OptionSheet(
                title = "强调色",
                subtitle = "用于按钮、选中状态与重点信息",
                options = AccentColor.entries.map { it.label to (it == accent) },
                onSelect = { index ->
                    prefs.setAccent(AccentColor.entries[index])
                    sheet = null
                },
                onDismiss = { sheet = null },
            )

            Sheet.Engine -> OptionSheet(
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

            Sheet.Decoder -> OptionSheet(
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

            Sheet.VideoCache -> OptionSheet(
                title = "视频缓存大小",
                subtitle = "缓存已播放的数据，减少回看与网络抖动造成的卡顿",
                options = VideoCacheSize.entries.map { it.label to (it == videoCacheSize) },
                onSelect = { index ->
                    component.playbackPreferences.setVideoCacheSize(VideoCacheSize.entries[index])
                    sheet = null
                },
                onDismiss = { sheet = null },
            )

            Sheet.DanmakuSource -> DanmakuSourceDialog(
                sources = danmakuSources,
                activeSourceId = danmakuActiveSourceId,
                onSelect = { component.danmakuPreferences.selectSource(it) },
                onAdd = { name, url -> component.danmakuPreferences.addSource(name, url) },
                onUpdate = component.danmakuPreferences::updateSource,
                onRemove = component.danmakuPreferences::removeSource,
                onDismiss = { sheet = null },
            )

            Sheet.DanmakuBlocked -> DanmakuBlockedDialog(
                words = danmakuBlocked,
                onAdd = component.danmakuPreferences::addBlockedWord,
                onRemove = component.danmakuPreferences::removeBlockedWord,
                onDismiss = { sheet = null },
            )

            Sheet.SkipSegments -> SkipSegmentDialog(
                skipMode = skipMode,
                onSelectSkipMode = component.skipSegmentPreferences::setSkipMode,
                onDismiss = { sheet = null },
            )

            Sheet.UserAgent -> UserAgentDialog(
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

            Sheet.WatchTogether -> WatchJoinDialog(
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

            Sheet.WatchEndpoint -> WatchEndpointDialog(
                current = watchEndpoint,
                onSave = {
                    component.watchTogetherPreferences.setEndpoint(it)
                    sheet = null
                },
                onReset = {
                    component.watchTogetherPreferences.setEndpoint(
                        WatchTogetherPreferences.DEFAULT_ENDPOINT,
                    )
                    sheet = null
                },
                onDismiss = { sheet = null },
            )

            Sheet.WatchProfile -> WatchProfileDialog(
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
                message = "将清除图片缓存，下次浏览时重新下载。" +
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
                        if (watchEndpoint.trimEnd('/') == WatchTogetherPreferences.DEFAULT_ENDPOINT.trimEnd('/')) "默认 ›" else "自定义 ›",
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = Dimens.contentTop, bottom = TabBarInset),
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
        Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .pressable(onClickLabel = "返回", onClick = onBack)
                .touchTarget()
                .size(34.dp)
                .glass(AppShapes.thumb, palette.card3, palette.border),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.ChevronLeft,
                "返回",
                tint = palette.text,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = AppTypography.section.strong, color = palette.text)
            subtitle?.let {
                Text(it, style = AppTypography.caption.regular, color = palette.sub2)
            }
        }
    }
}

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
                    modifier = Modifier
                        .pressable(onClick = onAction)
                        .touchTarget()
                        .glass(
                            shape = GlassShapes.chip,
                            fill = palette.card2,
                            border = palette.border,
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        content()
    }
}

/** Settings row — `--pg-card2`, `padding:13px 16px`, `500 13px` / `400 12px Manrope`. */
@Composable
internal fun SettingRow(
    title: String,
    value: String,
    embedded: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val rowModifier = Modifier
        .fillMaxWidth()
        .let {
            if (embedded) it else {
                it.glass(AppShapes.control, palette.card2, palette.border)
            }
        }
        .let { if (onClick != null) it.pressable(onClick = onClick) else it }
        .heightIn(min = MinTouchTarget)
        .padding(horizontal = 16.dp, vertical = 13.dp)
    BoxWithConstraints(rowModifier) {
        val stacked = largeText || windowWidthTier(maxWidth) == WindowWidthTier.Compact
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = AppTypography.body.medium, color = palette.text, maxLines = 2)
                Text(value, style = AppTypography.body.regular, color = palette.sub2, maxLines = 2)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
private fun DownloadRow(value: String, embedded: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val rowModifier = Modifier
        .fillMaxWidth()
        .let {
            if (embedded) it else {
                it.glass(AppShapes.control, palette.card2, palette.border)
            }
        }
        .pressable(onClick = onClick)
        .heightIn(min = MinTouchTarget)
        .padding(horizontal = 16.dp, vertical = 13.dp)
    val label: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Download,
                null,
                tint = accent.accent,
                modifier = Modifier.size(16.dp),
            )
            Text("下载与离线库", style = AppTypography.body.medium, color = palette.text)
        }
    }
    BoxWithConstraints(rowModifier) {
        val stacked = largeText || windowWidthTier(maxWidth) == WindowWidthTier.Compact
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                label()
                Text(value, style = AppTypography.body.regular, color = palette.sub2, maxLines = 2)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { label() }
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

/**
 * Second-level page for the launch animation.
 *
 * Every card runs the real choreography on a loop rather than showing a still: the whole
 * difference between the variants is in the motion, so a static thumbnail would say nothing.
 * They are stacked full width rather than sat side by side because the squash is what you are
 * here to judge, and it does not read at thumbnail size.
 */
@Composable
private fun SplashSettingsScreen(
    onBack: () -> Unit,
    prefs: ThemePreferences,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val enabled by prefs.splashAnimation.collectAsState()
    val selected by prefs.splashVariant.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(
            top = Dimens.contentTop,
            bottom = TabBarInset,
            start = Dimens.pageHorizontal,
            end = Dimens.pageHorizontal,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .pressable(onClickLabel = "返回", onClick = onBack)
                        .touchTarget()
                        .size(34.dp)
                        .glass(AppShapes.thumb, palette.card3, palette.border),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.ChevronLeft,
                        "返回",
                        tint = palette.text,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Text(
                    "开屏动画",
                    style = AppTypography.section.strong,
                    color = palette.text,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }

        item {
            SwitchRow("启动时播放", enabled) { prefs.setSplashAnimation(it) }
        }

        if (enabled) {
            items(SplashAnimation.entries) { variant ->
                val active = variant == selected
                Column(
                    Modifier
                        .fillMaxWidth()
                        .pressable(role = Role.RadioButton) { prefs.setSplashVariant(variant) }
                        .semantics { this.selected = active }
                        .clip(AppShapes.card)
                        .background(palette.card2)
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) accent.border else palette.border,
                            shape = AppShapes.card,
                        )
                        .padding(horizontal = 14.dp, vertical = 14.dp),
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
                }
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
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                if (embedded) it else {
                    it.glass(AppShapes.control, palette.card2, palette.border)
                }
            }
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = AppTypography.body.medium,
            color = palette.text,
            maxLines = 2,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        PillSwitch(checked)
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
            )
            .heightIn(min = MinTouchTarget)
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
    onResumePlayback: (PlaybackRecoverySnapshot) -> Unit,
) {
    if (page == ProfilePage.Splash) {
        SplashSettingsScreen(onBack = onBack, prefs = themePreferences)
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

    LaunchedEffect(syncManager) {
        syncManager.syncAll()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(
            top = Dimens.contentTop,
            bottom = TabBarInset,
            start = Dimens.pageHorizontal,
            end = Dimens.pageHorizontal,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .pressable(onClickLabel = "返回", onClick = onBack)
                        .touchTarget()
                        .size(34.dp)
                        .glass(AppShapes.thumb, palette.card3, palette.border),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.ChevronLeft,
                        "返回",
                        tint = palette.text,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Column(Modifier.padding(start = 12.dp)) {
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
private fun RecoveryAction(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Text(
        label,
        style = AppTypography.caption.strong,
        color = accent.accent,
        modifier = Modifier
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
    val shape = AppShapes.pill
    val offTrack = palette.sub2.copy(alpha = if (palette.isDark) 0.24f else 0.26f)
    val offBorder = palette.sub2.copy(alpha = if (palette.isDark) 0.62f else 0.78f)
    val knobFill = if (checked) accent.onAccent else palette.background
    val knobBorder = if (checked) {
        accent.onAccent.copy(alpha = 0.88f)
    } else {
        palette.sub2.copy(alpha = 0.72f)
    }
    Box(
        Modifier
            .width(38.dp)
            .height(22.dp)
            .glass(
                shape,
                if (checked) {
                    accent.accent
                } else {
                    offTrack
                },
                if (checked) accent.border else offBorder,
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(18.dp)
                .glass(CircleShape, knobFill, knobBorder),
        )
    }
}

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
