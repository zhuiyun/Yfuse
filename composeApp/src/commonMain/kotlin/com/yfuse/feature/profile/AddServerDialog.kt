package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import com.yfuse.core.network.validateEmbyServerEndpoint
import com.yfuse.feature.servers.PlexAccountUiState
import com.yfuse.feature.servers.QuickConnectUiState
import com.yfuse.feature.servers.ServerFormInput
import com.yfuse.feature.servers.ServerFormRow
import com.yfuse.feature.servers.ServerProtocolSegment
import com.yfuse.feature.servers.ServerProviderSegment
import com.yfuse.feature.servers.ServersIntent
import com.yfuse.feature.servers.ServersState
import com.yfuse.feature.servers.hasInputSince
import com.yfuse.feature.servers.rememberServerConnectionIntent
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.core.designsystem.liquidGlass as glass

/**
 * 添加服务器.
 *
 * This used to be a four-step full-screen wizard reached by a route push, which is a
 * lot of ceremony for "type an address and sign in" — and it buried the LAN scan two
 * steps deep. Everything now lives in one modal: discovered servers on top for the
 * common case, the manual form below for the rest.
 */
@Composable
fun AddServerDialog(
    state: ServersState,
    onIntent: (ServersIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val sendIntent = rememberServerConnectionIntent(state, onIntent)
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val form = state.form
    val uriHandler = LocalUriHandler.current
    val editing = state.editingServerId != null
    val endpointValidation = validateEmbyServerEndpoint(form.url, form.httpRiskAccepted)
    val requestLanScan =
        rememberLocalNetworkPermissionRequest(
            onGranted = { sendIntent(ServersIntent.Scan) },
            onDenied = { sendIntent(ServersIntent.LocalNetworkPermissionDenied) },
        )
    // What the form held when it opened: empty to add a server, its saved details to edit one.
    val openedForm = remember(state.editingServerId) { form }
    val holdsInput = form.hasInputSince(openedForm)
    var confirmDiscard by remember { mutableStateOf(false) }
    // One rule for the button and for the keyboard's 完成, so the keyboard cannot send a form the
    // button would refuse.
    val canSubmit =
        (form.canSubmit || (editing && !state.connectionEdited)) &&
            endpointValidation.allowed &&
            (!editing || form.serverName.isNotBlank())
    val submit = { if (canSubmit && !form.submitting) sendIntent(ServersIntent.Submit) }

    GlassDialog(
        onDismiss = onDismiss,
        scrollable = false,
        // A flick that landed a little fast, or a tap beside the panel, used to throw away an
        // address, an account and a password in one move. Once anything is entered, closing asks.
        dragToDismiss = !holdsInput,
        confirmDismiss = {
            if (holdsInput) confirmDiscard = true
            !holdsInput
        },
    ) {
        OverlayHeader(
            title =
                when {
                    state.reauthenticating -> "重新登录"
                    editing -> "编辑服务器"
                    else -> "添加服务器"
                },
            subtitle =
                when {
                    state.reauthenticating -> "保存的登录已失效，重新登录后即可继续浏览"
                    editing -> "名称可直接修改；连接信息变更后需重新登录"
                    else -> "连接 Emby、Jellyfin 或 Plex 服务器"
                },
            onClose = onDismiss,
        )

        // Only the fields scroll. The header, validation message, and submit button remain
        // visible even on a short screen or while the IME is open.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FieldLabel("局域网发现") {
                Row(
                    Modifier
                        .pressable(enabled = !state.scanning) {
                            requestLanScan()
                        }.touchTarget()
                        .glass(AppShapes.thumb, palette.card2, palette.border)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.scanning) {
                        OrbProgress(size = 10.dp, color = accent.accent)
                    }
                    Text(
                        if (state.scanning) "扫描中" else "扫描",
                        style = AppTypography.caption.strong,
                        color = accent.accent,
                    )
                }
            }

            when {
                state.discovered.isNotEmpty() ->
                    state.discovered.forEach { server ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable { sendIntent(ServersIntent.SelectDiscovered(server)) }
                                .glass(AppShapes.chip, palette.card2, palette.border)
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .background(accent.accent, AppShapes.thumb),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    server.name.take(1).uppercase(),
                                    style = AppTypography.caption.strong,
                                    color = accent.onAccent,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    server.name,
                                    style = AppTypography.body.strong,
                                    color = palette.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    server.address,
                                    style = AppTypography.caption.regular,
                                    color = palette.sub2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                AppIcons.ChevronRight,
                                null,
                                tint = palette.sub2,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }

                state.scanError != null ->
                    Text(
                        state.scanError,
                        style = AppTypography.caption.medium.copy(lineHeight = 16.8.sp),
                        color = palette.error,
                    )

                !state.scanning ->
                    Text(
                        "点击扫描查找同一网络下的服务器，或在下方手动填写。",
                        style = AppTypography.caption.regular.copy(lineHeight = 16.8.sp),
                        color = palette.hint,
                    )
            }

            Spacer(Modifier.height(4.dp))
            FieldLabel("服务器信息")
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(AppShapes.card, palette.card2, palette.border),
            ) {
                ServerFormRow(label = "服务类型", divider = true, labelBottomPadding = 6.dp) {
                    Row(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ServerProviderSegment("Emby", MediaServerKind.Emby, form.kind, Modifier.weight(1f)) {
                            sendIntent(ServersIntent.ProviderChanged(MediaServerKind.Emby))
                        }
                        ServerProviderSegment("Jellyfin", MediaServerKind.Jellyfin, form.kind, Modifier.weight(1f)) {
                            sendIntent(ServersIntent.ProviderChanged(MediaServerKind.Jellyfin))
                        }
                        ServerProviderSegment("Plex", MediaServerKind.Plex, form.kind, Modifier.weight(1f)) {
                            sendIntent(ServersIntent.ProviderChanged(MediaServerKind.Plex))
                        }
                    }
                }
                ServerFormRow(label = "协议", divider = true, labelBottomPadding = 6.dp) {
                    Row(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ServerProtocolSegment("HTTPS", form.https, Modifier.weight(1f)) {
                            sendIntent(ServersIntent.ProtocolChanged(true))
                        }
                        ServerProtocolSegment("HTTP", !form.https, Modifier.weight(1f)) {
                            sendIntent(ServersIntent.ProtocolChanged(false))
                        }
                    }
                }
                ServerFormInput(
                    label = "地址",
                    value = form.host,
                    placeholder = "media.example.com",
                    enabled = !form.submitting,
                    keyboardType = KeyboardType.Uri,
                    divider = true,
                ) { sendIntent(ServersIntent.HostChanged(it)) }
                ServerFormInput(
                    label = "端口",
                    value = form.port,
                    enabled = !form.submitting,
                    keyboardType = KeyboardType.Number,
                    divider = true,
                ) { sendIntent(ServersIntent.PortChanged(it)) }
                // Last rather than first: it is optional, and it used to stand between the user
                // and the address, which is what actually connects.
                ServerFormInput(
                    label = "显示名称",
                    value = form.serverName,
                    placeholder = if (editing) "输入服务器名称" else "留空使用服务器名称",
                    enabled = !form.submitting,
                    divider = false,
                ) { sendIntent(ServersIntent.ServerNameChanged(it)) }
            }
            Spacer(Modifier.height(4.dp))
            FieldLabel("账号")
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(AppShapes.card, palette.card2, palette.border),
            ) {
                if (form.kind == MediaServerKind.Plex) {
                    ServerFormRow(label = "Plex 云账号", divider = true) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (val account = state.plexAccount) {
                                PlexAccountUiState.Idle,
                                PlexAccountUiState.Cancelled,
                                PlexAccountUiState.Expired,
                                is PlexAccountUiState.Error,
                                -> {
                                    Text(
                                        when (account) {
                                            PlexAccountUiState.Expired -> "登录码已过期，请重新开始。"
                                            PlexAccountUiState.Cancelled -> "已取消 Plex 账号登录。"
                                            is PlexAccountUiState.Error -> account.message
                                            else -> "使用 plex.tv 账号发现服务器，并保留远程与 Relay 线路。"
                                        },
                                        style = AppTypography.caption.regular,
                                        color =
                                            if (account is PlexAccountUiState.Error) {
                                                palette.error
                                            } else {
                                                palette.sub2
                                            },
                                    )
                                    OverlayButton(
                                        label = if (account == PlexAccountUiState.Idle) "使用 Plex 账号登录" else "重新登录",
                                        onClick = { sendIntent(ServersIntent.StartPlexAccountSignIn) },
                                        modifier = Modifier.fillMaxWidth(),
                                        tone = OverlayButtonTone.Plain,
                                    )
                                }
                                PlexAccountUiState.Starting,
                                PlexAccountUiState.LoadingAccount,
                                PlexAccountUiState.LoadingServers,
                                PlexAccountUiState.Connecting,
                                -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OrbProgress(size = 14.dp, color = accent.accent)
                                        Text(
                                            when (account) {
                                                PlexAccountUiState.Starting -> "正在申请登录码…"
                                                PlexAccountUiState.LoadingAccount -> "正在读取 Plex Home…"
                                                PlexAccountUiState.LoadingServers -> "正在发现账号服务器…"
                                                else -> "正在验证服务器线路…"
                                            },
                                            style = AppTypography.caption.medium,
                                            color = palette.body,
                                        )
                                    }
                                }
                                is PlexAccountUiState.AwaitingAuthorization -> {
                                    Text(
                                        "登录码  ${account.code}",
                                        style = AppTypography.body.strong,
                                        color = palette.text,
                                    )
                                    Text(
                                        "在 Plex 页面确认后会自动继续。",
                                        style = AppTypography.caption.regular,
                                        color = palette.sub2,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OverlayButton(
                                            label = "打开 Plex 授权页",
                                            onClick = { uriHandler.openUri(account.authUrl) },
                                            modifier = Modifier.weight(1f),
                                            tone = OverlayButtonTone.Plain,
                                        )
                                        OverlayButton(
                                            label = "取消",
                                            onClick = { sendIntent(ServersIntent.CancelPlexAccountSignIn) },
                                            modifier = Modifier.weight(1f),
                                            tone = OverlayButtonTone.Plain,
                                        )
                                    }
                                }
                                is PlexAccountUiState.SelectingHomeUser -> {
                                    Text(
                                        "选择 Plex Home 用户",
                                        style = AppTypography.caption.strong,
                                        color = palette.text,
                                    )
                                    if (account.users.any { it.pinProtected }) {
                                        ServerFormInput(
                                            label = "Home PIN（受保护用户）",
                                            value = state.plexHomePin,
                                            divider = false,
                                            password = true,
                                            keyboardType = KeyboardType.Number,
                                        ) { sendIntent(ServersIntent.PlexHomePinChanged(it)) }
                                    }
                                    account.error?.let {
                                        Text(it, style = AppTypography.caption.medium, color = palette.error)
                                    }
                                    account.users.forEach { user ->
                                        PlexChoiceRow(
                                            title = user.name,
                                            subtitle =
                                                buildString {
                                                    append(if (user.admin) "管理员" else "家庭用户")
                                                    if (user.pinProtected) append(" · 需要 PIN")
                                                },
                                        ) { sendIntent(ServersIntent.SelectPlexHomeUser(user.id)) }
                                    }
                                }
                                is PlexAccountUiState.SelectingServer -> {
                                    Text(
                                        "选择 Plex Media Server",
                                        style = AppTypography.caption.strong,
                                        color = palette.text,
                                    )
                                    account.servers.forEach { server ->
                                        PlexChoiceRow(
                                            title = server.name,
                                            subtitle =
                                                "${if (server.owned) "自有" else "共享"} · ${server.routeCount} 条线路",
                                        ) { sendIntent(ServersIntent.SelectPlexCloudServer(server.id)) }
                                    }
                                }
                            }
                        }
                    }
                    ServerFormInput(
                        label = "手动 Plex Token（备用）",
                        value = form.password,
                        placeholder = if (editing) "修改连接时重新填写 Token" else "输入 X-Plex-Token",
                        enabled = !form.submitting,
                        password = true,
                        divider = false,
                        onSubmit = submit,
                    ) { sendIntent(ServersIntent.PasswordChanged(it)) }
                } else {
                    ServerFormInput(
                        label = "用户名",
                        value = form.username,
                        placeholder = "输入用户名",
                        enabled = !form.submitting,
                        divider = true,
                        autofillType = ContentType.Username,
                    ) { sendIntent(ServersIntent.UsernameChanged(it)) }
                    ServerFormInput(
                        label = "密码",
                        value = form.password,
                        placeholder =
                            when {
                                state.reauthenticating -> "输入密码重新登录"
                                editing -> "仅修改名称时无需填写"
                                else -> "留空表示无密码"
                            },
                        enabled = !form.submitting,
                        password = true,
                        divider = true,
                        autofillType = ContentType.Password,
                        onSubmit = submit,
                        autoFocus = state.reauthenticating,
                    ) { sendIntent(ServersIntent.PasswordChanged(it)) }
                    // The only reachable add-server UI, now that the full-page 添加服务器
                    // (`ServersScreen`) is gone — Quick Connect used to live there and nowhere
                    // else. `state`/`onIntent` already carry the whole ServersStore contract,
                    // so this is the same StartQuickConnect/CancelQuickConnect machine, just
                    // rendered as one more row instead of a separate floating card.
                    ServerFormRow(label = "Quick Connect", divider = false) {
                        QuickConnectSection(
                            state = state.quickConnect,
                            enabled = form.canStartQuickConnect,
                            onStart = { sendIntent(ServersIntent.StartQuickConnect) },
                            onCancel = { sendIntent(ServersIntent.CancelQuickConnect) },
                        )
                    }
                }
            }

            // Picking a discovered server loads its public users; offer them as one tap
            // instead of making the name be typed from memory.
            if (form.kind != MediaServerKind.Plex && state.publicUsers.isNotEmpty()) {
                Row(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.publicUsers
                        .map { it.Name }
                        .filter(String::isNotBlank)
                        .take(4)
                        .forEach { name ->
                            val selected = name == form.username
                            Text(
                                name,
                                style =
                                    if (selected) {
                                        AppTypography.caption.strong
                                    } else {
                                        AppTypography.caption.medium
                                    },
                                color = if (selected) accent.accent else palette.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier
                                        .pressable(role = Role.RadioButton) {
                                            sendIntent(ServersIntent.SelectPublicUser(name))
                                        }.semantics { this.selected = selected }
                                        .touchTarget()
                                        .glass(
                                            shape = AppShapes.thumb,
                                            fill =
                                                if (selected) {
                                                    accent.container
                                                } else {
                                                    palette.card2
                                                },
                                            border =
                                                if (selected) {
                                                    accent.border
                                                } else {
                                                    palette.border
                                                },
                                        ).padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                }
            }
        }

        // Outside the scrolling form, immediately above the button that produced it. As the
        // form's last child it was drawn past the bottom of a 400dp scroll box that the
        // fields already overflow, so a failed connection looked like the button simply
        // stopped spinning — the one moment the dialog has something to say and it was the
        // one thing the user could not see.
        if (form.error != null) {
            Text(
                form.error,
                style = AppTypography.caption.medium,
                color = palette.error,
                // Read out as it appears: with TalkBack on, the spinner stopping was all that said
                // the connection had failed.
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).liveStatus(assertive = true),
            )
        }

        OverlayButton(
            label =
                when {
                    state.reauthenticating -> "重新登录"
                    editing -> "保存修改"
                    else -> "连接到服务器"
                },
            onClick = { sendIntent(ServersIntent.Submit) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            tone = OverlayButtonTone.Primary,
            enabled = canSubmit,
            loading = form.submitting,
        )

        // Opened from inside the form, so it belongs to this dialog rather than to its owner.
        if (confirmDiscard) {
            ConfirmDialog(
                title = "放弃更改？",
                message = if (editing) "对这台服务器的修改还没有保存。" else "已填写的服务器信息还没有保存。",
                confirmLabel = "放弃",
                dismissLabel = "继续编辑",
                destructive = true,
                onConfirm = {
                    confirmDiscard = false
                    onDismiss()
                },
                onDismiss = { confirmDiscard = false },
            )
        }
    }
}

/**
 * Emby/Jellyfin Quick Connect: sign in with a code shown here and approved from an
 * already-logged-in client, instead of typing a password. Mirrors the Plex 云账号 row
 * above it — a short status line, the code once one is issued, and one action whose
 * label and target (start/cancel/retry) follow [state].
 */
@Composable
private fun QuickConnectSection(
    state: QuickConnectUiState,
    enabled: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val busy = state is QuickConnectUiState.CheckingSupport || state is QuickConnectUiState.AwaitingApproval
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            QuickConnectUiState.Idle ->
                Text(
                    "如果服务器支持 Quick Connect，可用验证码登录，无需输入密码。",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            QuickConnectUiState.CheckingSupport ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OrbProgress(size = 14.dp, color = accent.accent)
                    Text("正在请求服务器…", style = AppTypography.caption.medium, color = palette.body)
                }
            is QuickConnectUiState.AwaitingApproval -> {
                Text(
                    "验证码  ${state.code}",
                    style = AppTypography.body.strong,
                    color = palette.text,
                    modifier = Modifier.semantics { contentDescription = "Quick Connect 验证码 ${state.code}" },
                )
                Text(
                    "在已登录的客户端中输入此验证码，批准后会自动继续。",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
            is QuickConnectUiState.Unsupported ->
                Text(state.reason, style = AppTypography.caption.regular, color = palette.sub2)
            QuickConnectUiState.Expired ->
                Text("验证码已过期，请重新获取。", style = AppTypography.caption.medium, color = palette.error)
            QuickConnectUiState.Cancelled ->
                Text("已取消 Quick Connect。", style = AppTypography.caption.regular, color = palette.sub2)
            is QuickConnectUiState.Error ->
                Text(state.message, style = AppTypography.caption.medium, color = palette.error)
        }
        val actionLabel =
            when (state) {
                QuickConnectUiState.Idle -> "使用 Quick Connect"
                QuickConnectUiState.CheckingSupport,
                is QuickConnectUiState.AwaitingApproval,
                -> "取消"
                is QuickConnectUiState.Unsupported -> null
                QuickConnectUiState.Expired,
                QuickConnectUiState.Cancelled,
                is QuickConnectUiState.Error,
                -> "重新获取"
            }
        if (actionLabel != null) {
            OverlayButton(
                label = actionLabel,
                onClick = if (busy) onCancel else onStart,
                modifier = Modifier.fillMaxWidth(),
                tone = OverlayButtonTone.Plain,
                enabled = busy || enabled,
            )
        }
    }
}

@Composable
private fun PlexChoiceRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .touchTarget()
            .glass(AppShapes.thumb, palette.card3, palette.border)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(AppIcons.ChevronRight, null, tint = palette.sub2, modifier = Modifier.size(13.dp))
    }
}

/** Group label, optionally with a trailing control on the same baseline. */
@Composable
private fun FieldLabel(
    text: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = AppTypography.caption.strong.copy(letterSpacing = 0.4.sp),
            color = palette.sub2,
        )
        trailing?.invoke()
    }
}
