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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import com.yfuse.feature.servers.QuickConnectUiState
import com.yfuse.feature.servers.ServersIntent
import com.yfuse.feature.servers.ServersState
import com.yfuse.feature.servers.ServersTabComponent
import com.yfuse.feature.servers.hasInputSince
import com.yfuse.feature.servers.rememberServerConnectionIntent
import com.yfuse.tv.focus.FocusCandidate
import com.yfuse.tv.focus.requestFocusWhenAttached
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
        ) +
            state.servers.mapIndexed { index, server ->
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
                serverGridState.revealForRestore(anchor.fallbackIndex.coerceIn(0, serverCandidates.lastIndex))
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
                Text("服务器", color = TvOnSurface, fontSize = TvType.display, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Emby、Jellyfin 与 Plex",
                    color = TvOnSurfaceMuted,
                    fontSize = TvType.caption,
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
                modifier = Modifier.fillMaxSize().tvFocusBleed(),
                contentPadding = TvFocusBleedPadding,
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

    // The store closes the dialog itself once a server connects. Held here, it plays its exit
    // from the state it last showed instead of vanishing in a frame.
    DialogPresence(state.takeIf { it.dialogVisible }) { shown ->
        TvServerDialog(
            state = shown,
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
        selectable = true,
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
                    Text(server.iconEmoji ?: server.serverName.take(1), fontSize = TvType.section)
                }
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(
                        server.serverName,
                        color = if (focused) Color.White else TvOnSurface,
                        fontSize = TvType.section,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        "${server.kind.name} · ${server.userName}",
                        color = if (focused) Color.White.copy(alpha = 0.68f) else TvOnSurfaceMuted,
                        fontSize = TvType.caption,
                    )
                }
            }
            Column {
                Text(
                    server.baseUrl,
                    color = if (focused) Color.White.copy(alpha = 0.74f) else TvOnSurfaceMuted,
                    fontSize = TvType.caption,
                    maxLines = 1,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        if (selected) "当前服务器" else "确定键切换并打开",
                        color = if (selected) TvAccent else Color.White.copy(alpha = 0.66f),
                        fontSize = TvType.caption,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "长按菜单可编辑",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = TvType.caption,
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
    val sendIntent = rememberServerConnectionIntent(state, onIntent)
    val hostRequester = remember { FocusRequester() }
    val requestScan =
        rememberLocalNetworkPermissionRequest(
            onGranted = { sendIntent(ServersIntent.Scan) },
            onDenied = { sendIntent(ServersIntent.LocalNetworkPermissionDenied) },
        )
    LaunchedEffect(Unit) { hostRequester.requestFocusWhenAttached() }
    DisposableEffect(focusMemory) {
        onDispose { focusMemory.requestLastForRoute("servers") }
    }
    // What the form held when it opened: empty to add a server, its saved details to edit one.
    val openedForm = remember(state.editingServerId) { state.form }
    val holdsInput = state.form.hasInputSince(openedForm)
    var confirmDiscard by remember { mutableStateOf(false) }
    GlassDialog(
        onDismiss = { sendIntent(ServersIntent.DismissDialog) },
        maxWidth = 920.dp,
        contentPadding = 28.dp,
        // One Back too many used to throw away an address, an account and a password typed in on a
        // remote, the slowest keyboard there is. Once anything is entered, closing asks first.
        confirmDismiss = {
            if (holdsInput) confirmDiscard = true
            !holdsInput
        },
    ) {
        val dismiss = overlayDismiss { sendIntent(ServersIntent.DismissDialog) }
        Column(
            Modifier
                .fillMaxWidth()
                .tvFocusScope(trapFocus = true),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Text(
                if (state.editingServerId == null) "添加服务器" else "编辑服务器",
                color = TvOnSurface,
                fontSize = TvType.section,
                fontWeight = FontWeight.ExtraBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MediaServerKind.entries.forEach { kind ->
                    TvActionButton(
                        label = kind.name,
                        stableId = "server-dialog:provider:${kind.name}",
                        focusScope = "server-dialog:provider",
                        focusMemory = focusMemory,
                        onClick = { sendIntent(ServersIntent.ProviderChanged(kind)) },
                        modifier = Modifier.width(136.dp),
                        selected = kind == state.form.kind,
                        selectable = true,
                    )
                }
                TvActionButton(
                    label = if (state.form.https) "HTTPS" else "HTTP",
                    stableId = "server-dialog:protocol",
                    focusScope = "server-dialog:provider",
                    focusMemory = focusMemory,
                    onClick = { sendIntent(ServersIntent.ProtocolChanged(!state.form.https)) },
                    modifier = Modifier.width(136.dp),
                    selected = state.form.https,
                )
            }
            // Typing an address on a remote is slow, so the LAN scan goes above the form: the
            // common case is one press to fill everything in.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionButton(
                    label = if (state.scanning) "正在搜索…" else "搜索局域网",
                    stableId = "server-dialog:scan",
                    focusScope = "server-dialog:discovery",
                    focusMemory = focusMemory,
                    onClick = { if (!state.scanning) requestScan() },
                    modifier = Modifier.width(176.dp),
                    icon = AppIcons.Search,
                )
            }
            state.scanError?.let { Text(it, color = TvWarning, fontSize = TvType.caption) }
            if (state.discovered.isNotEmpty()) {
                Text(
                    "在本网络中找到 ${state.discovered.size} 台服务器",
                    color = TvOnSurfaceMuted,
                    fontSize = TvType.caption,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.discovered.take(4).forEach { found ->
                        TvActionButton(
                            label = found.name.ifBlank { found.address },
                            stableId = "server-dialog:discovered:${found.id}",
                            focusScope = "server-dialog:discovery",
                            focusMemory = focusMemory,
                            onClick = { sendIntent(ServersIntent.SelectDiscovered(found)) },
                            modifier = Modifier.width(212.dp),
                            icon = AppIcons.Server,
                        )
                    }
                }
            } else if (!state.scanning && state.scanError == null) {
                Text(
                    "搜索会寻找同一网络里的 Emby 与 Jellyfin 服务器，Plex 请直接登录账号。",
                    color = TvOnSurfaceMuted,
                    fontSize = TvType.caption,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvServerTextField(
                    value = state.form.serverName,
                    label = "服务器名称（可选）",
                    stableId = "server-dialog:name",
                    focusMemory = focusMemory,
                    onValueChange = { sendIntent(ServersIntent.ServerNameChanged(it)) },
                    modifier = Modifier.weight(1f),
                )
                TvServerTextField(
                    value = state.form.host,
                    label = "主机或完整地址",
                    stableId = "server-dialog:host",
                    focusMemory = focusMemory,
                    onValueChange = { sendIntent(ServersIntent.HostChanged(it)) },
                    modifier = Modifier.weight(1.4f),
                    focusRequester = hostRequester,
                )
                TvServerTextField(
                    value = state.form.port,
                    label = "端口",
                    stableId = "server-dialog:port",
                    focusMemory = focusMemory,
                    onValueChange = { sendIntent(ServersIntent.PortChanged(it)) },
                    modifier = Modifier.width(130.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvServerTextField(
                    value = state.form.username,
                    label = if (state.form.kind == MediaServerKind.Plex) "Plex 用户" else "用户名",
                    stableId = "server-dialog:username",
                    focusMemory = focusMemory,
                    onValueChange = { sendIntent(ServersIntent.UsernameChanged(it)) },
                    modifier = Modifier.weight(1f),
                )
                TvServerTextField(
                    value = state.form.password,
                    label = if (state.form.kind == MediaServerKind.Plex) "Plex Token" else "密码",
                    stableId = "server-dialog:password",
                    focusMemory = focusMemory,
                    onValueChange = { sendIntent(ServersIntent.PasswordChanged(it)) },
                    modifier = Modifier.weight(1f),
                    secret = true,
                )
            }
            when (val quick = state.quickConnect) {
                is QuickConnectUiState.AwaitingApproval ->
                    Text(
                        "快速连接代码：${quick.code}，请在另一台设备批准登录。",
                        color = TvAccent,
                        fontSize = TvType.body,
                        fontWeight = FontWeight.Bold,
                    )
                is QuickConnectUiState.Error -> Text(quick.message, color = TvDanger, fontSize = TvType.caption)
                is QuickConnectUiState.Unsupported ->
                    Text(quick.reason, color = TvOnSurfaceMuted, fontSize = TvType.caption)
                QuickConnectUiState.Expired -> Text("快速连接代码已过期", color = TvWarning, fontSize = TvType.caption)
                else -> Unit
            }
            state.form.error?.let { Text(it, color = TvDanger, fontSize = TvType.caption) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TvActionButton(
                    label = "取消",
                    stableId = "server-dialog:cancel",
                    focusScope = "server-dialog:actions",
                    focusMemory = focusMemory,
                    onClick = dismiss,
                    modifier = Modifier.width(132.dp),
                )
                Spacer(Modifier.width(10.dp))
                if (state.form.canStartQuickConnect) {
                    TvActionButton(
                        label = "快速连接",
                        stableId = "server-dialog:quick-connect",
                        focusScope = "server-dialog:actions",
                        focusMemory = focusMemory,
                        onClick = { sendIntent(ServersIntent.StartQuickConnect) },
                        modifier = Modifier.width(158.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                TvActionButton(
                    label = if (state.form.submitting) "正在登录" else "连接",
                    stableId = "server-dialog:submit",
                    focusScope = "server-dialog:actions",
                    focusMemory = focusMemory,
                    onClick = { if (state.form.canSubmit) sendIntent(ServersIntent.Submit) },
                    modifier = Modifier.width(150.dp),
                    icon = AppIcons.ChevronRight,
                    primary = state.form.canSubmit,
                )
            }
        }
        if (confirmDiscard) {
            TvConfirmDialog(
                title = "放弃已填写的内容？",
                message =
                    if (state.editingServerId == null) {
                        "已填写的服务器信息还没有保存。"
                    } else {
                        "对这台服务器的修改还没有保存。"
                    },
                confirmLabel = "放弃",
                dismissLabel = "继续编辑",
                focusScope = "server-dialog:discard",
                focusMemory = focusMemory,
                onConfirm = {
                    confirmDiscard = false
                    sendIntent(ServersIntent.DismissDialog)
                },
                onDismiss = { confirmDiscard = false },
            )
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
        // Material's field type is the phone's 13sp body; a remote-driven form is read from a sofa.
        textStyle = LocalTextStyle.current.copy(fontSize = TvType.body),
        label = { Text(label, fontSize = TvType.caption) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}
