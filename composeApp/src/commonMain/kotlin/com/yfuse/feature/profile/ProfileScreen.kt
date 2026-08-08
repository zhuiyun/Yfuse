package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.yfuse.core.data.VideoCacheSize
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.activeOr
import com.yfuse.core.designsystem.continuousRounded
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccent
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.SplashPreview
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.SavedServer
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.SyncMutationKind
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.servers.ServersIntent
import kotlinx.coroutines.launch

/** Which option sheet is open — the prototype's `settingsSheetTab`. */
private enum class Sheet {
    ThemeMode,
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
    val serversState by component.serversStore.states
        .collectAsState(component.serversStore.state)

    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var confirmRemove by remember { mutableStateOf<SavedServer?>(null) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var serversExpanded by remember { mutableStateOf(false) }
    var pageStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var offlineToPlay by remember { mutableStateOf<OfflineMedia?>(null) }
    var recoveryToPlay by remember {
        mutableStateOf<Pair<PlayerMediaItem, PlaybackRecoverySnapshot>?>(null)
    }
    val palette = LocalPalette.current
    val mainListState = rememberLazyListState()
    ScrollToTopOnReselect(mainListState)
    val screenScope = rememberCoroutineScope()
    val page = pageStack.lastOrNull()?.let { ProfilePage.valueOf(it) }

    fun openPage(target: ProfilePage) {
        pageStack = pageStack + target.name
    }

    fun closePage() {
        pageStack = pageStack.dropLast(1)
    }

    val addServerOpen = serversState.dialogVisible
    fun openAddServer() {
        component.serversStore.accept(ServersIntent.OpenAddDialog)
    }

    StatusBarIconStyle(darkIcons = !palette.isDark)
    PlatformBackHandler(
        enabled = page != null || sheet != null ||
            addServerOpen || confirmRemove != null || confirmClearCache,
    ) {
        when {
            confirmClearCache -> confirmClearCache = false
            confirmRemove != null -> confirmRemove = null
            addServerOpen -> component.serversStore.accept(ServersIntent.DismissDialog)
            sheet != null -> sheet = null
            else -> closePage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (page) {
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
                customUserAgent = if (customUserAgent.isBlank()) "应用默认 ›" else "已启用 ›",
                onEngine = { sheet = Sheet.Engine },
                onDecoder = { sheet = Sheet.Decoder },
                onAutoNext = prefs::setAutoNext,
                onVideoCache = { sheet = Sheet.VideoCache },
                onSkipSegments = { sheet = Sheet.SkipSegments },
                onUserAgent = { sheet = Sheet.UserAgent },
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
                customEndpoint = watchEndpoint.trimEnd('/') !=
                    WatchTogetherPreferences.DEFAULT_ENDPOINT.trimEnd('/'),
                onJoin = { sheet = Sheet.WatchTogether },
                onProfile = { sheet = Sheet.WatchProfile },
                onChatDanmaku = component.watchTogetherPreferences::setChatDanmakuEnabled,
                onChatPreview = component.watchTogetherPreferences::setChatPreviewEnabled,
                onEndpoint = { sheet = Sheet.WatchEndpoint },
            )

            ProfilePage.Appearance -> AppearanceSettingsScreen(
                onBack = ::closePage,
                mode = mode,
                accent = accent,
                splashSummary = if (splashAnimation) "${splashVariant.label} ›" else "已关闭 ›",
                reduceTransparency = reduceTransparency,
                largeText = largeText,
                reduceMotion = reduceMotion,
                onThemeMode = { sheet = Sheet.ThemeMode },
                onAccent = { sheet = Sheet.Accent },
                onSplash = { openPage(ProfilePage.Splash) },
                onReduceTransparency = prefs::setReduceTransparency,
                onLargeText = prefs::setLargeText,
                onReduceMotion = prefs::setReduceMotion,
            )

            ProfilePage.DataAndDiagnostics -> {
                val backupPayload = remember(
                    component,
                    state.servers,
                    state.currentServer?.id,
                ) {
                    component.exportServers()
                }
                DataAndDiagnosticsScreen(
                    onBack = ::closePage,
                    serverCount = state.servers.size,
                    backupPayload = backupPayload,
                    onImport = component::importServers,
                    onClearCache = { confirmClearCache = true },
                )
            }

            ProfilePage.Downloads,
            ProfilePage.Recovery,
            ProfilePage.Splash,
            -> ProfileUtilityScreen(
                page = page,
                onBack = ::closePage,
                offlineManager = component.offlineMedia,
                onPlayOffline = { offlineToPlay = it },
                syncManager = component.syncManager,
                playbackRecovery = component.playbackRecovery,
                themePreferences = prefs,
                onResumePlayback = { snapshot ->
                    component.recoveryItem(snapshot)?.let { recoveryToPlay = it to snapshot }
                },
            )

            null -> {
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
                    Section(
                        title = "我的服务器",
                        action = "+ 添加",
                        onAction = ::openAddServer,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CollapsibleSummaryRow(
                                title = "${state.servers.size} 台服务器",
                                subtitle = state.currentServer?.serverName ?: "尚未连接",
                                expanded = serversExpanded,
                                onClick = { serversExpanded = !serversExpanded },
                            )
                            if (serversExpanded) {
                                state.servers.forEach { server ->
                                    ServerRow(
                                        server = server,
                                        isCurrent = server.id == state.currentServer?.id,
                                        onClick = {
                                            component.store.accept(
                                                ProfileIntent.SwitchServer(server.id),
                                            )
                                        },
                                        onLongClick = { confirmRemove = server },
                                        onEdit = {
                                            component.serversStore.accept(
                                                ServersIntent.EditServer(server),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Section(title = "设置") {
                        SettingsCard {
                            SettingRow(
                                "播放",
                                "${engine.label} · ${decoder.label} ›",
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
                    Section(title = "数据与应用") {
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
                                "数据与诊断",
                                "${state.servers.size} 台服务器 · 缓存与日志 ›",
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

        offlineToPlay?.takeIf { it.playable }?.let { offline ->
            val path = offline.localPath ?: return@let
            PlayerLauncher(
                items = listOf(
                    PlayerMediaItem(
                        id = offline.itemId,
                        url = "file://$path",
                        transcodeUrl = "file://$path",
                        title = offline.title,
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
                title = "默认播放器内核",
                subtitle = "用于新播放；播放页内的切换只影响当前播放",
                options = PlayerEngine.selectable.map { it.label to (it == engine) },
                onSelect = { index ->
                    prefs.setEngine(PlayerEngine.selectable[index])
                    sheet = null
                },
                onDismiss = { sheet = null },
            )

            Sheet.Decoder -> OptionSheet(
                title = "解码内核",
                subtitle = "硬解更省电，软解兼容性更好",
                options = DecoderMode.entries.map { it.label to (it == decoder) },
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

        if (addServerOpen) {
            AddServerDialog(
                state = serversState,
                onIntent = component.serversStore::accept,
                onDismiss = { component.serversStore.accept(ServersIntent.DismissDialog) },
            )
        }

        confirmRemove?.let { server ->
            val isCurrent = server.id == state.currentServer?.id
            ConfirmDialog(
                title = "移除服务器",
                message = if (isCurrent) {
                    "将退出「${server.serverName}」并从列表中移除，已下载的离线内容会保留。"
                } else {
                    "将从列表中移除「${server.serverName}」，之后可以重新登录。"
                },
                confirmLabel = "移除",
                destructive = true,
                onConfirm = {
                    confirmRemove = null
                    if (isCurrent) {
                        component.store.accept(ProfileIntent.Logout)
                    } else {
                        component.onRemoveServer(server.id)
                    }
                },
                onDismiss = { confirmRemove = null },
            )
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
private fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    engine: PlayerEngine,
    decoder: DecoderMode,
    autoNext: Boolean,
    videoCacheSize: VideoCacheSize,
    skipSegments: String,
    customUserAgent: String,
    onEngine: () -> Unit,
    onDecoder: () -> Unit,
    onAutoNext: (Boolean) -> Unit,
    onVideoCache: () -> Unit,
    onSkipSegments: () -> Unit,
    onUserAgent: () -> Unit,
) {
    SettingsPage(
        title = "播放",
        subtitle = "播放器、解码与播放行为",
        onBack = onBack,
    ) {
        item {
            Section(title = "播放体验") {
                SettingsCard {
                    SettingRow("默认播放器内核", "${engine.label} ›", true, onEngine)
                    SettingsDivider()
                    SettingRow("解码内核", "${decoder.label} ›", true, onDecoder)
                    SettingsDivider()
                    SwitchRow("自动播放下一集", autoNext, true, onAutoNext)
                    SettingsDivider()
                    SettingRow("视频缓存大小", "${videoCacheSize.label} ›", true, onVideoCache)
                    SettingsDivider()
                    SettingRow("片头片尾", skipSegments, true, onSkipSegments)
                }
            }
        }
        item {
            Section(title = "网络与兼容") {
                SettingsCard {
                    SettingRow("自定义 User-Agent", customUserAgent, true, onUserAgent)
                }
            }
        }
    }
}

@Composable
private fun DanmakuSettingsScreen(
    onBack: () -> Unit,
    sourceSummary: String,
    blockedSummary: String,
    onSources: () -> Unit,
    onBlockedWords: () -> Unit,
) {
    SettingsPage(
        title = "弹幕",
        subtitle = "来源与内容过滤",
        onBack = onBack,
    ) {
        item {
            Section(title = "弹幕设置") {
                SettingsCard {
                    SettingRow("弹幕来源", sourceSummary, true, onSources)
                    SettingsDivider()
                    SettingRow("屏蔽词", blockedSummary, true, onBlockedWords)
                }
            }
        }
    }
}

@Composable
private fun WatchTogetherSettingsScreen(
    onBack: () -> Unit,
    connected: Boolean,
    roomCode: String?,
    nickname: String,
    chatDanmaku: Boolean,
    chatPreview: Boolean,
    customEndpoint: Boolean,
    onJoin: () -> Unit,
    onProfile: () -> Unit,
    onChatDanmaku: (Boolean) -> Unit,
    onChatPreview: (Boolean) -> Unit,
    onEndpoint: () -> Unit,
) {
    SettingsPage(
        title = "一起看",
        subtitle = "房间、资料与聊天显示",
        onBack = onBack,
    ) {
        item {
            Section(title = "房间") {
                SettingsCard {
                    SettingRow(
                        if (connected) "当前房间" else "加入房间",
                        if (connected) "房间 ${roomCode.orEmpty()} · 查看 ›" else "输入房间码 ›",
                        true,
                        onJoin,
                    )
                    SettingsDivider()
                    SettingRow("一起看资料", "$nickname ›", true, onProfile)
                }
            }
        }
        item {
            Section(title = "聊天显示") {
                SettingsCard {
                    SwitchRow("聊天弹幕", chatDanmaku, true, onChatDanmaku)
                    SettingsDivider()
                    SwitchRow("聊天消息浮层", chatPreview, true, onChatPreview)
                }
            }
        }
        item {
            Section(title = "连接") {
                SettingsCard {
                    SettingRow(
                        "一起看服务器",
                        if (customEndpoint) "自定义 ›" else "默认 ›",
                        true,
                        onEndpoint,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    mode: ThemeMode,
    accent: AccentColor,
    splashSummary: String,
    reduceTransparency: Boolean,
    largeText: Boolean,
    reduceMotion: Boolean,
    onThemeMode: () -> Unit,
    onAccent: () -> Unit,
    onSplash: () -> Unit,
    onReduceTransparency: (Boolean) -> Unit,
    onLargeText: (Boolean) -> Unit,
    onReduceMotion: (Boolean) -> Unit,
) {
    SettingsPage(
        title = "外观与辅助",
        subtitle = "主题、颜色与辅助显示",
        onBack = onBack,
    ) {
        item {
            Section(title = "外观") {
                SettingsCard {
                    SettingRow("主题模式", "${mode.label} ›", true, onThemeMode)
                    SettingsDivider()
                    SettingRow("强调色", "${accent.label}色 ›", true, onAccent)
                    SettingsDivider()
                    SettingRow("开屏动画", splashSummary, true, onSplash)
                }
            }
        }
        item {
            Section(title = "辅助功能") {
                SettingsCard {
                    SwitchRow("减少透明效果", reduceTransparency, true, onReduceTransparency)
                    SettingsDivider()
                    SwitchRow("大号文字", largeText, true, onLargeText)
                    SettingsDivider()
                    SwitchRow("减少动画", reduceMotion, true, onReduceMotion)
                }
            }
        }
    }
}

@Composable
private fun DataAndDiagnosticsScreen(
    onBack: () -> Unit,
    serverCount: Int,
    backupPayload: String,
    onImport: (String) -> Result<Int>,
    onClearCache: () -> Unit,
) {
    SettingsPage(
        title = "数据与诊断",
        subtitle = "迁移、缓存与问题排查",
        onBack = onBack,
    ) {
        item {
            Box(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
                ServerBackupTools(
                    payload = backupPayload,
                    serverCount = serverCount,
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
private fun SettingsPage(
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
                .size(34.dp)
                .pressable(onClick = onBack)
                .glass(continuousRounded(12.dp), palette.card3, palette.border),
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
            Text(title, style = sc(20f, 700), color = palette.text)
            subtitle?.let {
                Text(it, style = mr(10.5f, 400), color = palette.sub2)
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card, palette.card2, palette.border)
            .clip(GlassShapes.card),
        content = content,
    )
}

@Composable
private fun CollapsibleSummaryRow(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .glass(
                shape = GlassShapes.card,
                fill = palette.card2,
                border = palette.border,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .glass(
                    shape = continuousRounded(10.dp),
                    fill = Brand.Primary.copy(alpha = 0.10f),
                    border = Brand.Primary.copy(alpha = 0.20f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Server,
                contentDescription = null,
                tint = Brand.Primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = sc(12.5f, 700), color = palette.text)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = mr(10.5f, 400),
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (expanded) "收起" else "展开",
            style = mr(10.5f, 600),
            color = Brand.Primary,
        )
        Icon(
            AppIcons.ChevronDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = Brand.Primary,
            modifier = Modifier.size(12.dp),
        )
    }
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
    Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = sc(12f, 700).copy(letterSpacing = 0.5.sp), color = palette.sub2)
            if (action != null) {
                Text(
                    action,
                    style = mr(11f, 600),
                    color = Brand.Primary,
                    modifier = Modifier
                        .pressable(onClick = onAction)
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

/**
 * Server row — `radius:14px`, `padding:11px 12px`, `gap:11px`; current uses
 * `rgba(61,100,201,.1)` over `rgba(61,100,201,.3)`, others `--pg-card2`-ish white.
 */
@Composable
private fun ServerRow(
    server: SavedServer,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = GlassShapes.chip
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onLongClick = onLongClick, onClick = onClick)
            .glass(
                shape = shape,
                fill = if (isCurrent) Brand.Primary.copy(alpha = 0.1f) else palette.card2,
                border = if (isCurrent) Brand.Primary.copy(alpha = 0.3f) else palette.border,
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A solid server colour keeps identity without adding a second material.
        Box(
            Modifier
                .size(34.dp)
                .clip(continuousRounded(9.dp))
                .background(serverColor(server.id)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                server.serverName.take(1).uppercase(),
                style = mr(12f, 700),
                color = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                server.serverName,
                style = sc(12.5f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isCurrent) Brand.Online else Brand.Offline),
                )
                Text(
                    if (isCurrent) "当前使用 · ${server.userName}" else server.userName,
                    style = mr(10f, 400),
                    color = palette.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent) {
            Icon(AppIcons.Check, null, tint = Brand.Primary, modifier = Modifier.size(13.dp))
        } else {
            Text("切换", style = mr(11f, 400), color = Brand.Offline)
        }
        Box(
            Modifier
                .size(22.dp)
                .pressable(onClick = onEdit)
                .clip(GlassShapes.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Edit, "编辑服务器", tint = palette.sub, modifier = Modifier.size(13.dp))
        }
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
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                if (embedded) it else {
                    it.glass(continuousRounded(13.dp), palette.card2, palette.border)
                }
            }
            .let { if (onClick != null) it.pressable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = sc(13f, 500), color = palette.text, maxLines = 1)
        Text(value, style = mr(12f, 400), color = palette.sub2, maxLines = 1)
    }
}

@Composable
private fun DownloadRow(value: String, embedded: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                if (embedded) it else {
                    it.glass(continuousRounded(13.dp), palette.card2, palette.border)
                }
            }
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Download,
                null,
                tint = Brand.Primary,
                modifier = Modifier.size(16.dp),
            )
            Text("下载与离线库", style = sc(13f, 500), color = palette.text)
        }
        Text(value, style = mr(12f, 400), color = palette.sub2, maxLines = 1)
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
    val accent = LocalAccent.current
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
                        .size(34.dp)
                        .pressable(onClick = onBack)
                        .glass(continuousRounded(12.dp), palette.card3, palette.border),
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
                    style = sc(20f, 700),
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
                        .pressable { prefs.setSplashVariant(variant) }
                        .clip(continuousRounded(18.dp))
                        .background(palette.card2)
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) accent.color else palette.border,
                            shape = continuousRounded(18.dp),
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
                            style = sc(15f, if (active) 700 else 500),
                            color = if (active) accent.color else palette.text,
                        )
                        if (active) {
                            Icon(
                                AppIcons.Check,
                                null,
                                tint = accent.color,
                                modifier = Modifier.padding(start = 6.dp).size(15.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        variant.description,
                        style = mr(12f, 400),
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
private fun SwitchRow(
    title: String,
    checked: Boolean,
    embedded: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                if (embedded) it else {
                    it.glass(continuousRounded(13.dp), palette.card2, palette.border)
                }
            }
            .pressable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = sc(13f, 500), color = palette.text, maxLines = 1)
        PillSwitch(checked)
    }
}

@Composable
private fun SettingsDivider() {
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
    Row(
        Modifier
            .fillMaxWidth()
            .pressable { onChange(!checked) }
            .glass(continuousRounded(13.dp), palette.card2, palette.border)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = sc(13f, 500), color = palette.text, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = sc(10.5f, 400),
                color = palette.sub2,
                maxLines = 2,
            )
        }
        PillSwitch(checked)
    }
}

@Composable
private fun ProfileUtilityScreen(
    page: ProfilePage,
    onBack: () -> Unit,
    offlineManager: OfflineMediaManager,
    onPlayOffline: (OfflineMedia) -> Unit,
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
    val palette = LocalPalette.current
    val downloads by offlineManager.items.collectAsState()
    val wifiOnly by offlineManager.wifiOnly.collectAsState()

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
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .pressable(onClick = onBack)
                        .glass(continuousRounded(12.dp), palette.card3, palette.border),
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
                    "下载",
                    style = sc(20f, 700),
                    color = palette.text,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }

        item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(continuousRounded(18.dp), palette.card, palette.border)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text("离线下载占用", style = sc(12.5f, 700), color = palette.text)
                        Text(
                            "${formatOfflineBytes(downloads.sumOf { it.downloadedBytes })} 已使用",
                            style = mr(10.5f, 400),
                            color = palette.sub2,
                        )
                    }
                }
            }

            item {
                Box(Modifier.fillMaxWidth().clip(continuousRounded(18.dp))) {
                    DescribedSwitchRow(
                        "仅在 Wi-Fi 下下载",
                        "避免占用蜂窝流量",
                        wifiOnly,
                    ) { offlineManager.setWifiOnly(it) }
                }
            }

            if (downloads.isEmpty()) item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        AppIcons.Download,
                        null,
                        tint = palette.hint,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "还没有下载内容\n在详情页点击下载，即可离线观看",
                        style = sc(11.5f, 400, lineHeight = 18f),
                        color = palette.hint,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            if (downloads.isNotEmpty()) {
                items(
                    count = downloads.size,
                    key = { downloads[it].id },
                ) { index ->
                    val download = downloads[index]
                    OfflineDownloadRow(
                        item = download,
                        onPlay = { onPlayOffline(download) },
                        onPause = { offlineManager.pause(download.id) },
                        onResume = { offlineManager.resume(download.id) },
                        onRemove = { offlineManager.remove(download.id) },
                    )
                }
        }
    }
}

@Composable
private fun RecoveryCenterScreen(
    onBack: () -> Unit,
    syncManager: ServerSyncManager,
    playbackRecovery: PlaybackRecoveryStore,
    onResumePlayback: (PlaybackRecoverySnapshot) -> Unit,
) {
    val palette = LocalPalette.current
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
                        .size(34.dp)
                        .pressable(onClick = onBack)
                        .glass(continuousRounded(12.dp), palette.card3, palette.border),
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
                    Text("播放恢复中心", style = sc(20f, 700), color = palette.text)
                    Text(
                        "本地断点、服务器同步与冲突处理",
                        style = mr(10.5f, 400),
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
                        style = mr(11.5f, 400),
                        color = palette.sub2,
                    )
                } else {
                    Text(
                        current.title.ifBlank { "未命名视频" },
                        style = sc(13f, 700),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${current.positionMs.asRecoveryClock()} / " +
                            "${current.durationMs.asRecoveryClock()} · ${current.engine}",
                        style = mr(10.5f, 400),
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
                        style = mr(11f, 500),
                        color = palette.sub2,
                    )
                    RecoveryAction("立即同步") {
                        scope.launch { syncManager.syncAll(force = true) }
                    }
                }
                if (sync.statuses.isEmpty()) {
                    Text("正在读取服务器状态…", style = mr(11f, 400), color = palette.hint)
                }
                sync.statuses.sortedBy { it.serverName }.forEach { status ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(status.serverName, style = sc(12f, 600), color = palette.text)
                            Text(
                                status.error ?: when {
                                    status.syncing -> "同步中…"
                                    status.online == true -> "${status.itemCount} 项 · 已连接"
                                    status.online == false -> "离线"
                                    else -> "等待同步"
                                },
                                style = mr(10f, 400),
                                color = if (status.error != null) Brand.Danger else palette.sub2,
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
                            style = sc(10.5f, 600),
                            color = if (status.online == false) Brand.Danger else Brand.Primary,
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
                            style = mr(11f, 500),
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
                                style = sc(12f, 700),
                                color = palette.text,
                            )
                            Text(
                                "本地：${if (conflict.mutation.desired) "开启" else "关闭"} · " +
                                    "服务器：${if (conflict.serverValue) "开启" else "关闭"}",
                                style = mr(10.5f, 400),
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
        Text(title, style = sc(13f, 700), color = palette.text)
        content()
    }
}

@Composable
private fun RecoveryAction(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        label,
        style = sc(10.5f, 700),
        color = Brand.Primary,
        modifier = Modifier
            .pressable(onClick = onClick)
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

@Composable
private fun OfflineDownloadRow(
    item: OfflineMedia,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card, palette.card, palette.border)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = sc(12.5f, 700),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    when (item.status) {
                        DownloadStatus.Queued -> "等待下载"
                        DownloadStatus.WaitingForWifi -> "等待 Wi-Fi"
                        DownloadStatus.Downloading ->
                            "${formatOfflineBytes(item.downloadedBytes)} / " +
                                formatOfflineBytes(item.totalBytes)
                        DownloadStatus.Paused -> "已暂停 · ${formatOfflineBytes(item.downloadedBytes)}"
                        DownloadStatus.Completed -> "已完成 · ${formatOfflineBytes(item.downloadedBytes)}"
                        DownloadStatus.Failed -> item.error ?: "下载失败"
                    },
                    style = mr(10.5f, 400),
                    color = if (item.status == DownloadStatus.Failed) Brand.Danger else palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                when (item.status) {
                    DownloadStatus.Completed -> "播放"
                    DownloadStatus.Downloading -> "暂停"
                    else -> "继续"
                },
                style = sc(11f, 700),
                color = Brand.Primary,
                modifier = Modifier
                    .pressable {
                        when (item.status) {
                            DownloadStatus.Completed -> onPlay()
                            DownloadStatus.Downloading -> onPause()
                            else -> onResume()
                        }
                    }
                    .glass(GlassShapes.chip, palette.card2, palette.border)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                AppIcons.Close,
                contentDescription = "删除离线文件",
                tint = palette.sub2,
                modifier = Modifier
                    .size(30.dp)
                    .pressable(onClick = onRemove)
                    .glass(CircleShape, palette.card2, palette.border)
                    .padding(8.dp),
            )
        }
        if (item.status != DownloadStatus.Completed) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(continuousRounded(2.dp))
                    .background(palette.border),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(item.progress)
                        .height(4.dp)
                        .background(Brand.Primary),
                )
            }
        }
    }
}

private fun formatOfflineBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L ->
        "${(value / 1024.0 / 1024.0 / 1024.0 * 10).toInt() / 10.0} GB"
    value >= 1024L * 1024L -> "${value / 1024L / 1024L} MB"
    value >= 1024L -> "${value / 1024L} KB"
    else -> "$value B"
}

/**
 * `width:38px;height:22px;border-radius:11px` track — `#3D64C9` on, `rgba(0,0,0,.15)`
 * off — with an 18px knob inset 2px.
 */
@Composable
private fun PillSwitch(checked: Boolean) {
    val palette = LocalPalette.current
    val shape = continuousRounded(11.dp)
    Box(
        Modifier
            .width(38.dp)
            .height(22.dp)
            .glass(
                shape,
                if (checked) {
                    Brand.Primary.copy(alpha = 0.72f)
                } else if (palette.isDark) {
                    Color.White.copy(alpha = 0.12f)
                } else {
                    Color.White.copy(alpha = 0.38f)
                },
                if (checked) Color.White.copy(alpha = 0.44f) else palette.border,
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(18.dp)
                .glass(CircleShape, Color.White.copy(alpha = 0.82f), Color.White),
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
) {
    val palette = LocalPalette.current
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(title = title, subtitle = subtitle, onClose = onDismiss)
        Column {
            options.forEachIndexed { index, (label, selected) ->
                OverlayOptionRow(
                    label = label,
                    selected = selected,
                    onClick = { onSelect(index) },
                )
                if (index < options.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(1.dp)
                            .background(palette.border),
                    )
                }
            }
        }
    }
}

/** Stable per-server solid colour, deliberately free of gradients. */
private fun serverColor(id: String): Color =
    serverColors[(id.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % serverColors.size]

private val serverColors = listOf(
    Color(0xFF6689D3),
    Color(0xFFC98F5B),
    Color(0xFF8298C1),
    Color(0xFF7198CB),
)
