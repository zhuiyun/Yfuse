package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.app.hideBottomBarOnScroll
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.shadow
import com.yfuse.feature.servers.ServersIntent
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.SavedServer
import com.yfuse.core.data.PlaybackRecoverySnapshot
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.SyncMutationKind
import kotlinx.coroutines.launch

/** Which option sheet is open — the prototype's `settingsSheetTab`. */
private enum class Sheet { Engine, Decoder, DanmakuSource, UserAgent }

private enum class ProfilePage { Downloads, Recovery }

/** 个人中心 — `padding:52px 18px 100px; gap:18px`. */
@Composable
fun ProfileScreen(component: ProfileComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val prefs = component.themePreferences
    val mode by prefs.mode.collectAsState()
    val engine by prefs.engine.collectAsState()
    val decoder by prefs.decoder.collectAsState()
    val autoNext by prefs.autoNext.collectAsState()
    val danmakuUrl by component.danmakuPreferences.urlTemplate.collectAsState()
    val customUserAgent by component.userAgentPreferences.userAgent.collectAsState()
    val offlineItems by component.offlineMedia.items.collectAsState()
    val recoverySnapshot by component.playbackRecovery.snapshot.collectAsState()
    val syncState by component.syncManager.state.collectAsState()
    val serversState by component.serversStore.states
        .collectAsState(component.serversStore.state)

    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var confirmRemove by remember { mutableStateOf<SavedServer?>(null) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var serversExpanded by remember { mutableStateOf(false) }
    var migrationExpanded by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<ProfilePage?>(null) }
    var offlineToPlay by remember { mutableStateOf<OfflineMedia?>(null) }
    var recoveryToPlay by remember {
        mutableStateOf<Pair<PlayerMediaItem, PlaybackRecoverySnapshot>?>(null)
    }
    val palette = LocalPalette.current

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
            else -> page = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (page != null) {
            ProfileUtilityScreen(
                page = page!!,
                onBack = { page = null },
                offlineManager = component.offlineMedia,
                onPlayOffline = { offlineToPlay = it },
                syncManager = component.syncManager,
                playbackRecovery = component.playbackRecovery,
                onResumePlayback = { snapshot ->
                    component.recoveryItem(snapshot)?.let { recoveryToPlay = it to snapshot }
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding().hideBottomBarOnScroll(),
                contentPadding = PaddingValues(top = Dimens.contentTop, bottom = TabBarInset),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
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
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Section(title = "服务器迁移") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CollapsibleSummaryRow(
                                title = "迁移服务器",
                                subtitle = "${state.servers.size} 个服务器，可迁移登录状态",
                                expanded = migrationExpanded,
                                onClick = { migrationExpanded = !migrationExpanded },
                            )
                            if (migrationExpanded) {
                                ServerBackupTools(
                                    payload = component.exportServers(),
                                    serverCount = state.servers.size,
                                    onImport = component::importServers,
                                )
                            }
                        }
                    }
                }

                item {
                    Section(title = "播放设置") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .glass(GlassShapes.card, palette.card2, palette.border)
                                .clip(GlassShapes.card),
                        ) {
                            SettingRow(
                                "播放器内核",
                                engine.label,
                                embedded = true,
                                onClick = { sheet = Sheet.Engine },
                            )
                            SettingsDivider()
                            SettingRow(
                                "解码内核",
                                decoder.label,
                                embedded = true,
                                onClick = { sheet = Sheet.Decoder },
                            )
                            SettingsDivider()
                            SwitchRow(
                                "自动播放下一集",
                                autoNext,
                                embedded = true,
                            ) { prefs.setAutoNext(it) }
                            SettingsDivider()
                            SettingRow(
                                "弹幕链接",
                                if (danmakuUrl.isBlank()) "未配置 ›" else "已配置 ›",
                                embedded = true,
                                onClick = { sheet = Sheet.DanmakuSource },
                            )
                            SettingsDivider()
                            SettingRow(
                                "自定义 User-Agent",
                                if (customUserAgent.isBlank()) "系统默认 ›" else "已启用 ›",
                                embedded = true,
                                onClick = { sheet = Sheet.UserAgent },
                            )
                        }
                    }
                }

                item {
                    Section(title = "播放与同步") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .glass(GlassShapes.card, palette.card2, palette.border)
                                .clip(GlassShapes.card),
                        ) {
                            SettingRow(
                                "播放恢复中心",
                                when {
                                    syncState.conflicts.isNotEmpty() ->
                                        "${syncState.conflicts.size} 个冲突 ›"
                                    syncState.pendingCount > 0 ->
                                        "${syncState.pendingCount} 项待同步 ›"
                                    recoverySnapshot != null -> "可继续播放 ›"
                                    else -> "状态正常 ›"
                                },
                                embedded = true,
                                onClick = { page = ProfilePage.Recovery },
                            )
                        }
                    }
                }

                item {
                    Section(title = "离线") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                                .clip(RoundedCornerShape(13.dp)),
                        ) {
                            DownloadRow(
                                value = "${offlineItems.count { it.playable }} 项已下载 ›",
                                embedded = true,
                                onClick = { page = ProfilePage.Downloads },
                            )
                            SettingsDivider()
                            SettingRow(
                                "缓存占用",
                                "${formatOfflineBytes(offlineItems.sumOf { it.downloadedBytes })} ›",
                                embedded = true,
                                onClick = { confirmClearCache = true },
                            )
                        }
                    }
                }

                item {
                    Section(title = "外观") {
                        Column(Modifier.clip(GlassShapes.card)) {
                            SwitchRow("深色模式", mode == ThemeMode.Dark) { on ->
                                prefs.setMode(if (on) ThemeMode.Dark else ThemeMode.Light)
                            }
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
            Sheet.Engine -> OptionSheet(
                title = "播放器内核",
                subtitle = "决定用哪个引擎解码与渲染",
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

            Sheet.DanmakuSource -> DanmakuSourceDialog(
                current = danmakuUrl,
                onSave = {
                    component.danmakuPreferences.setUrlTemplate(it)
                    sheet = null
                },
                onClear = {
                    component.danmakuPreferences.setUrlTemplate("")
                    sheet = null
                },
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
                title = "清除缓存",
                message = "将清除图片与元数据缓存，下次浏览时重新下载。" +
                    "离线下载的影片不受影响。",
                confirmLabel = "清除",
                destructive = true,
                onConfirm = {
                    confirmClearCache = false
                    component.onClearCache()
                },
                onDismiss = { confirmClearCache = false },
            )
        }
    }
}

@Composable
private fun UserAgentDialog(
    current: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    val normalized = draft.trim()
    val palette = LocalPalette.current

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "自定义 User-Agent",
            subtitle = "应用于服务器 API 与视频取流请求；留空时使用系统默认值。",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(contentAlignment = Alignment.CenterStart) {
                if (draft.isBlank()) {
                    Text(
                        "例如：Yfuse/Android",
                        style = mr(12f, 500),
                        color = palette.hint,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { value ->
                        draft = value.replace("\r", "").replace("\n", "").take(512)
                    },
                    singleLine = true,
                    textStyle = mr(12f, 500).copy(color = palette.text),
                    cursorBrush = SolidColor(Brand.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "修改后新发起的请求立即生效；正在播放的媒体需重新进入播放器。",
            style = mr(10.5f, 400),
            color = palette.sub2,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (current.isNotBlank()) {
                OverlayButton(
                    label = "恢复默认",
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    tone = OverlayButtonTone.Destructive,
                )
            } else {
                OverlayButton("取消", onDismiss, Modifier.weight(1f))
            }
            OverlayButton(
                label = "保存",
                onClick = { onSave(normalized) },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
                enabled = normalized.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun DanmakuSourceDialog(
    current: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    val normalized = draft.trim()
    val valid = normalized.startsWith("http://") || normalized.startsWith("https://")
    val palette = LocalPalette.current

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "自定义弹幕链接",
            subtitle = "支持 Bilibili XML、DPlayer 与常见 JSON；可使用媒体占位符。",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(contentAlignment = Alignment.CenterStart) {
                if (draft.isBlank()) {
                    Text(
                        "https://example.com/danmaku?id={id}",
                        style = mr(12f, 500),
                        color = palette.hint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = mr(12f, 500).copy(color = palette.text),
                    cursorBrush = SolidColor(Brand.Primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "占位符：{id} 媒体 ID · {title} 标题 · {episode} 集序号 · {serverId} 服务器 ID",
            style = mr(10.5f, 400),
            color = palette.sub2,
        )
        if (normalized.isNotEmpty() && !valid) {
            Spacer(Modifier.height(6.dp))
            Text(
                "链接必须以 http:// 或 https:// 开头",
                style = sc(10.5f, 500),
                color = Brand.Danger,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (current.isNotBlank()) {
                OverlayButton(
                    label = "清除",
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    tone = OverlayButtonTone.Destructive,
                )
            } else {
                OverlayButton("取消", onDismiss, Modifier.weight(1f))
            }
            OverlayButton(
                label = "保存",
                onClick = { onSave(normalized) },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
                enabled = valid,
            )
        }
    }
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
            .glass(
                shape = GlassShapes.card,
                fill = palette.card2,
                border = palette.border,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .glass(
                    shape = RoundedCornerShape(10.dp),
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
private fun Section(
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
                        .glass(
                            shape = GlassShapes.chip,
                            fill = palette.card2,
                            border = palette.border,
                        )
                        .clickable(onClick = onAction)
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: SavedServer,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = GlassShapes.chip
    Row(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = shape,
                fill = if (isCurrent) Brand.Primary.copy(alpha = 0.1f) else palette.card2,
                border = if (isCurrent) Brand.Primary.copy(alpha = 0.3f) else palette.border,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A solid server colour keeps identity without adding a second material.
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
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
    }
}

/** Settings row — `--pg-card2`, `padding:13px 16px`, `500 13px` / `400 12px Manrope`. */
@Composable
private fun SettingRow(
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
                    it.glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                }
            }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
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
                    it.glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                }
            }
            .clickable(onClick = onClick)
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
                    it.glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                }
            }
            .clickable { onChange(!checked) }
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
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(LocalPalette.current.border.copy(alpha = 0.55f)),
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
            .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
            .clickable { onChange(!checked) }
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
    onResumePlayback: (PlaybackRecoverySnapshot) -> Unit,
) {
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
        modifier = Modifier.fillMaxSize().statusBarsPadding().hideBottomBarOnScroll(),
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
                        .glass(RoundedCornerShape(12.dp), palette.card3, palette.border)
                        .clickable(onClick = onBack),
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
                        .glass(RoundedCornerShape(18.dp), palette.card, palette.border)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text("存储空间", style = sc(12.5f, 700), color = palette.text)
                        Text(
                            "${formatOfflineBytes(downloads.sumOf { it.downloadedBytes })} 已使用",
                            style = mr(10.5f, 400),
                            color = palette.sub2,
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(palette.border),
                    )
                }
            }

            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
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
        modifier = Modifier.fillMaxSize().statusBarsPadding().hideBottomBarOnScroll(),
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
                        .glass(RoundedCornerShape(12.dp), palette.card3, palette.border)
                        .clickable(onClick = onBack),
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
                        scope.launch { syncManager.syncAll() }
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
            .glass(GlassShapes.chip, palette.card2, palette.border)
            .clickable(onClick = onClick)
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
                    .glass(GlassShapes.chip, palette.card2, palette.border)
                    .clickable {
                        when (item.status) {
                            DownloadStatus.Completed -> onPlay()
                            DownloadStatus.Downloading -> onPause()
                            else -> onResume()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                AppIcons.Close,
                contentDescription = "删除离线文件",
                tint = palette.sub2,
                modifier = Modifier
                    .size(30.dp)
                    .glass(CircleShape, palette.card2, palette.border)
                    .clickable(onClick = onRemove)
                    .padding(8.dp),
            )
        }
        if (item.status != DownloadStatus.Completed) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
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
    val shape = RoundedCornerShape(11.dp)
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
