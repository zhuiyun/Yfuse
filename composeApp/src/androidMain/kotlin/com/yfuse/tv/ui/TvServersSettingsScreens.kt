package com.yfuse.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.data.PlaybackAudioPassthrough
import com.yfuse.core.data.PlaybackFrameRateMatch
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.profile.ProfileComponent
import com.yfuse.feature.profile.ProfileIntent
import com.yfuse.feature.servers.QuickConnectUiState
import com.yfuse.feature.servers.ServersIntent
import com.yfuse.feature.servers.ServersState
import com.yfuse.feature.servers.ServersTabComponent
import com.yfuse.tv.focus.FocusCandidate
import com.yfuse.tv.focus.tvFocusScope

@Composable
internal fun TvServersScreen(
    component: ServersTabComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val refreshing by component.refreshing.collectAsState()
    val store = component.store
    val serverGridState = focusMemory.gridState("servers")
    val serverCandidates =
        listOf(
            FocusCandidate(
                targetId = focusMemory.targetId("servers:actions", "servers:refresh"),
                sectionId = "servers:actions",
                itemStableId = "servers:refresh",
                index = 0,
            ),
            FocusCandidate(
                targetId = focusMemory.targetId("servers:actions", "servers:add"),
                sectionId = "servers:actions",
                itemStableId = "servers:add",
                index = 1,
            ),
        ) + state.servers.mapIndexed { index, server ->
            val stableId = "servers:${server.kind.name.lowercase()}:${server.id}"
            FocusCandidate(
                targetId = focusMemory.targetId("servers:grid", stableId),
                sectionId = "servers:grid",
                itemStableId = stableId,
                index = index,
            )
        }
    TvRestoreRouteFocusEffect(
        route = "servers",
        focusMemory = focusMemory,
        fallback = contentRequester,
        contentGeneration = listOf(state.servers.size, state.defaultServerId, state.dialogVisible),
        candidates = serverCandidates,
        scrollToAnchor = { anchor ->
            if (anchor.sectionId == "servers:grid" && serverCandidates.isNotEmpty()) {
                serverGridState.scrollToItem(anchor.fallbackIndex.coerceIn(0, serverCandidates.lastIndex))
            }
        },
    )
    LaunchedEffect(component) { component.primeStats() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = TvSafeVertical, bottom = TvSafeVertical),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("服务器", color = TvOnSurface, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Emby、Jellyfin 与 Plex",
                    color = TvOnSurfaceMuted,
                    fontSize = 15.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionButton(
                    label = if (refreshing) "正在检测" else "检测连接",
                    stableId = "servers:refresh",
                    focusScope = "servers:actions",
                    focusMemory = focusMemory,
                    onClick = component::refreshAll,
                    modifier = Modifier.width(160.dp),
                    icon = AppIcons.Refresh,
                )
                TvActionButton(
                    label = "添加服务器",
                    stableId = "servers:add",
                    focusScope = "servers:actions",
                    focusMemory = focusMemory,
                    onClick = { store.accept(ServersIntent.OpenAddDialog) },
                    modifier = Modifier.width(180.dp),
                    icon = AppIcons.Add,
                    primary = true,
                    focusRequester = contentRequester,
                    navigationRequester = navigationRequester,
                    returnToNavigationOnLeft = true,
                )
            }
        }
        Spacer(Modifier.height(22.dp))

        if (state.servers.isEmpty()) {
            TvEmptyState(
                title = "连接你的媒体服务器",
                description = "支持 Emby、Jellyfin 和 Plex，登录后即可在电视上直链播放。",
                actionLabel = "添加服务器",
                onAction = { store.accept(ServersIntent.OpenAddDialog) },
                focusScope = "servers:empty",
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = serverGridState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(
                    state.servers,
                    key = { _, server -> "server:${server.kind.name}:${server.id}" },
                ) { index, server ->
                    TvServerCard(
                        server = server,
                        selected = server.id == state.defaultServerId,
                        focusMemory = focusMemory,
                        navigationRequester = navigationRequester,
                        returnToNavigationOnLeft = index % 3 == 0,
                        fallbackIndex = index,
                        onOpen = {
                            if (server.id != state.defaultServerId) {
                                store.accept(ServersIntent.SelectDefault(server.id))
                            }
                            component.onOpenLibrary()
                        },
                        onEdit = { store.accept(ServersIntent.EditServer(server)) },
                    )
                }
            }
        }
    }

    if (state.dialogVisible) {
        TvServerDialog(
            state = state,
            focusMemory = focusMemory,
            onIntent = store::accept,
        )
    }
}

@Composable
private fun TvServerCard(
    server: SavedServer,
    selected: Boolean,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    returnToNavigationOnLeft: Boolean,
    fallbackIndex: Int,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    TvFocusableSurface(
        stableId = "servers:${server.kind.name.lowercase()}:${server.id}",
        focusScope = "servers:grid",
        focusMemory = focusMemory,
        onClick = onOpen,
        selected = selected,
        navigationRequester = navigationRequester,
        returnToNavigationOnLeft = returnToNavigationOnLeft,
        fallbackIndex = fallbackIndex,
        serverId = server.id,
        profileId = server.userId,
        onContextMenu = onEdit,
        modifier = Modifier.fillMaxWidth().height(190.dp),
    ) { focused ->
        Column(
            Modifier.fillMaxSize().padding(19.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(48.dp)
                        .height(48.dp)
                        .background(
                            if (focused) Color.Black.copy(alpha = 0.12f) else TvAccent.copy(alpha = 0.15f),
                            RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(server.iconEmoji ?: server.serverName.take(1), fontSize = 23.sp)
                }
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(
                        server.serverName,
                        color = if (focused) Color.White else TvOnSurface,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        "${server.kind.name} · ${server.userName}",
                        color = if (focused) Color.White.copy(alpha = 0.68f) else TvOnSurfaceMuted,
                        fontSize = 13.sp,
                    )
                }
            }
            Column {
                Text(
                    server.baseUrl,
                    color = if (focused) Color.White.copy(alpha = 0.74f) else TvOnSurfaceMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        if (selected) "当前服务器" else "确定键切换并打开",
                        color = if (selected) TvAccent else Color.White.copy(alpha = 0.66f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "长按菜单可编辑",
                        color = Color.White.copy(alpha = 0.38f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvServerDialog(
    state: ServersState,
    focusMemory: TvUiFocusMemory,
    onIntent: (ServersIntent) -> Unit,
) {
    val hostRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { hostRequester.requestFocus() }
    Dialog(
        onDismissRequest = { onIntent(ServersIntent.DismissDialog) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .width(920.dp)
                .tvFocusScope(trapFocus = true)
                .background(Color(0xFF111720), RoundedCornerShape(22.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Text(
                if (state.editingServerId == null) "添加服务器" else "编辑服务器",
                color = TvOnSurface,
                fontSize = 29.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MediaServerKind.entries.forEach { kind ->
                    TvActionButton(
                        label = kind.name,
                        stableId = "server-dialog:provider:${kind.name}",
                        focusScope = "server-dialog:provider",
                        focusMemory = focusMemory,
                        onClick = { onIntent(ServersIntent.ProviderChanged(kind)) },
                        modifier = Modifier.width(136.dp),
                        selected = kind == state.form.kind,
                    )
                }
                TvActionButton(
                    label = if (state.form.https) "HTTPS" else "HTTP",
                    stableId = "server-dialog:protocol",
                    focusScope = "server-dialog:provider",
                    focusMemory = focusMemory,
                    onClick = { onIntent(ServersIntent.ProtocolChanged(!state.form.https)) },
                    modifier = Modifier.width(136.dp),
                    selected = state.form.https,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvServerTextField(
                    value = state.form.serverName,
                    label = "服务器名称（可选）",
                    stableId = "server-dialog:name",
                    focusMemory = focusMemory,
                    onValueChange = { onIntent(ServersIntent.ServerNameChanged(it)) },
                    modifier = Modifier.weight(1f),
                )
                TvServerTextField(
                    value = state.form.host,
                    label = "主机或完整地址",
                    stableId = "server-dialog:host",
                    focusMemory = focusMemory,
                    onValueChange = { onIntent(ServersIntent.HostChanged(it)) },
                    modifier = Modifier.weight(1.4f),
                    focusRequester = hostRequester,
                )
                TvServerTextField(
                    value = state.form.port,
                    label = "端口",
                    stableId = "server-dialog:port",
                    focusMemory = focusMemory,
                    onValueChange = { onIntent(ServersIntent.PortChanged(it)) },
                    modifier = Modifier.width(130.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvServerTextField(
                    value = state.form.username,
                    label = if (state.form.kind == MediaServerKind.Plex) "Plex 用户" else "用户名",
                    stableId = "server-dialog:username",
                    focusMemory = focusMemory,
                    onValueChange = { onIntent(ServersIntent.UsernameChanged(it)) },
                    modifier = Modifier.weight(1f),
                )
                TvServerTextField(
                    value = state.form.password,
                    label = if (state.form.kind == MediaServerKind.Plex) "Plex Token" else "密码",
                    stableId = "server-dialog:password",
                    focusMemory = focusMemory,
                    onValueChange = { onIntent(ServersIntent.PasswordChanged(it)) },
                    modifier = Modifier.weight(1f),
                    secret = true,
                )
            }
            when (val quick = state.quickConnect) {
                is QuickConnectUiState.AwaitingApproval ->
                    Text(
                        "快速连接代码：${quick.code}，请在另一台设备批准登录。",
                        color = TvAccent,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                is QuickConnectUiState.Error -> Text(quick.message, color = Color(0xFFFF9B9B))
                is QuickConnectUiState.Unsupported -> Text(quick.reason, color = TvOnSurfaceMuted)
                QuickConnectUiState.Expired -> Text("快速连接代码已过期", color = Color(0xFFFFC66D))
                else -> Unit
            }
            state.form.error?.let { Text(it, color = Color(0xFFFF9B9B), fontSize = 14.sp) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TvActionButton(
                    label = "取消",
                    stableId = "server-dialog:cancel",
                    focusScope = "server-dialog:actions",
                    focusMemory = focusMemory,
                    onClick = { onIntent(ServersIntent.DismissDialog) },
                    modifier = Modifier.width(132.dp),
                )
                Spacer(Modifier.width(10.dp))
                if (state.form.canStartQuickConnect) {
                    TvActionButton(
                        label = "快速连接",
                        stableId = "server-dialog:quick-connect",
                        focusScope = "server-dialog:actions",
                        focusMemory = focusMemory,
                        onClick = { onIntent(ServersIntent.StartQuickConnect) },
                        modifier = Modifier.width(158.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                TvActionButton(
                    label = if (state.form.submitting) "正在登录" else "连接",
                    stableId = "server-dialog:submit",
                    focusScope = "server-dialog:actions",
                    focusMemory = focusMemory,
                    onClick = { if (state.form.canSubmit) onIntent(ServersIntent.Submit) },
                    modifier = Modifier.width(150.dp),
                    icon = AppIcons.ChevronRight,
                    primary = state.form.canSubmit,
                )
            }
        }
    }
}

@Composable
private fun TvServerTextField(
    value: String,
    label: String,
    stableId: String,
    focusMemory: TvUiFocusMemory,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    secret: Boolean = false,
) {
    val requester = if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .then(requester)
                .onFocusChanged { if (it.isFocused) focusMemory.remember("server-dialog", stableId) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
internal fun TvSettingsScreen(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val mode by component.themePreferences.mode.collectAsState()
    val largeText by component.themePreferences.largeText.collectAsState()
    val reduceMotion by component.themePreferences.reduceMotion.collectAsState()
    val autoNext by component.themePreferences.autoNext.collectAsState()
    val frameRateMatch by component.playbackPreferences.frameRateMatch.collectAsState()
    val passthrough by component.playbackPreferences.audioPassthrough.collectAsState()
    TvRestoreRouteFocusEffect(
        route = "settings",
        focusMemory = focusMemory,
        fallback = contentRequester,
        contentGeneration = listOf(state.currentServer?.id, mode, largeText, reduceMotion),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = TvSafeVertical,
            bottom = TvSafeVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "settings-title") {
            Column {
                Text("设置", color = TvOnSurface, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    state.currentServer?.let { "${it.serverName} · ${it.userName}" }
                        ?: "尚未连接服务器",
                    color = TvOnSurfaceMuted,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        item(key = "settings-servers") {
            TvSettingRow(
                title = "服务器与账号",
                value = "${state.serverCount} 台服务器",
                stableId = "settings:servers",
                focusMemory = focusMemory,
                onClick = component.onOpenServers,
                icon = AppIcons.TabServers,
                focusRequester = contentRequester,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-theme") {
            TvSettingRow(
                title = "界面模式",
                value = mode.label,
                stableId = "settings:theme",
                focusMemory = focusMemory,
                onClick = {
                    val next = ThemeMode.entries[(ThemeMode.entries.indexOf(mode) + 1) % ThemeMode.entries.size]
                    component.themePreferences.setMode(next)
                },
                icon = AppIcons.Grid,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-large-text") {
            TvSettingRow(
                title = "大号文字",
                value = if (largeText) "已开启" else "已关闭",
                stableId = "settings:large-text",
                focusMemory = focusMemory,
                onClick = { component.themePreferences.setLargeText(!largeText) },
                icon = AppIcons.Info,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-reduce-motion") {
            TvSettingRow(
                title = "减少动态效果",
                value = if (reduceMotion) "已开启" else "已关闭",
                stableId = "settings:reduce-motion",
                focusMemory = focusMemory,
                onClick = { component.themePreferences.setReduceMotion(!reduceMotion) },
                icon = AppIcons.SkipMarkers,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-auto-next") {
            TvSettingRow(
                title = "自动播放下一集",
                value = if (autoNext) "已开启" else "已关闭",
                stableId = "settings:auto-next",
                focusMemory = focusMemory,
                onClick = { component.themePreferences.setAutoNext(!autoNext) },
                icon = AppIcons.Next,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-frame-rate") {
            TvSettingRow(
                title = "匹配内容帧率",
                value =
                    when (frameRateMatch) {
                        PlaybackFrameRateMatch.Disabled -> "关闭"
                        PlaybackFrameRateMatch.SeamlessOnly -> "仅无缝切换"
                        PlaybackFrameRateMatch.Always -> "始终匹配"
                    },
                stableId = "settings:frame-rate",
                focusMemory = focusMemory,
                onClick = {
                    val entries = PlaybackFrameRateMatch.entries
                    component.playbackPreferences.setFrameRateMatch(
                        entries[(entries.indexOf(frameRateMatch) + 1) % entries.size],
                    )
                },
                icon = AppIcons.Refresh,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "settings-passthrough") {
            TvSettingRow(
                title = "兼容音频直通",
                value = if (passthrough == PlaybackAudioPassthrough.Compatible) "已开启" else "已关闭",
                stableId = "settings:passthrough",
                focusMemory = focusMemory,
                onClick = {
                    component.playbackPreferences.setAudioPassthrough(
                        if (passthrough == PlaybackAudioPassthrough.Compatible) {
                            PlaybackAudioPassthrough.Disabled
                        } else {
                            PlaybackAudioPassthrough.Compatible
                        },
                    )
                },
                icon = AppIcons.AudioTrack,
                navigationRequester = navigationRequester,
            )
        }
        if (state.servers.size > 1) {
            item(key = "settings-account-title") {
                Text(
                    "快速切换服务器",
                    color = TvOnSurface,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            state.servers.forEach { server ->
                item(key = "settings-server:${server.id}") {
                    TvSettingRow(
                        title = server.serverName,
                        value = server.userName,
                        stableId = "settings:server:${server.id}",
                        focusMemory = focusMemory,
                        onClick = { component.store.accept(ProfileIntent.SwitchServer(server.id)) },
                        icon = AppIcons.Server,
                        selected = state.currentServer?.id == server.id,
                        navigationRequester = navigationRequester,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSettingRow(
    title: String,
    value: String,
    stableId: String,
    focusMemory: TvUiFocusMemory,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    navigationRequester: FocusRequester? = null,
) {
    TvFocusableSurface(
        stableId = stableId,
        focusScope = "settings",
        focusMemory = focusMemory,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(72.dp),
        selected = selected,
        focusRequester = focusRequester,
        navigationRequester = navigationRequester,
        returnToNavigationOnLeft = true,
        scaleWhenFocused = 1.015f,
    ) { focused ->
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = if (focused) Color.White else TvAccent,
            )
            Spacer(Modifier.width(17.dp))
            Text(
                title,
                color = TvOnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(value, color = TvOnSurfaceMuted, fontSize = 15.sp)
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = TvOnSurfaceMuted,
            )
        }
    }
}
