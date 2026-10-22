package com.yfuse.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.contentHandoff
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.motionAwareAnimateContentSize
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.network.validateEmbyServerEndpoint
import com.yfuse.feature.servers.PlexAccountUiState
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

/** Manual server details and credentials, shared by adding, editing and signing in again. */
@Composable
fun AddServerDialog(
    state: ServersState,
    onIntent: (ServersIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val sendIntent = rememberServerConnectionIntent(state, onIntent)
    val form = state.form
    val uriHandler = LocalUriHandler.current
    val editing = state.editingServerId != null
    val endpointValidation = validateEmbyServerEndpoint(form.url, form.httpRiskAccepted)
    // What the form held when it opened: empty to add a server, its saved details to edit one.
    val openedForm = remember(state.editingServerId) { form }
    val holdsInput = form.hasInputSince(openedForm)
    var confirmDiscard by remember { mutableStateOf(false) }
    // One rule for the button and for the keyboard's 完成, so the keyboard cannot send a form the
    // button would refuse. The name-only shortcut is not open while signing in again: that needs
    // the credentials, so a Plex server offers 重新登录 once a token is in or its account signs in.
    val canSubmit =
        (form.canSubmit || (editing && !state.connectionEdited && !state.reauthenticating)) &&
            endpointValidation.allowed &&
            (!editing || form.serverName.isNotBlank())
    val submit = { if (canSubmit && !form.submitting) sendIntent(ServersIntent.Submit) }

    GlassDialog(
        onDismiss = onDismiss,
        scrollable = false,
        // Once anything is entered, closing asks before discarding the form.
        confirmDismiss = {
            if (holdsInput) confirmDiscard = true
            !holdsInput
        },
    ) {
        val palette = LocalPalette.current
        val accent = LocalAccentColors.current
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
            FieldLabel("服务器信息")
            ServerFormSection {
                ServerFormRow(label = "服务类型", divider = false, labelBottomPadding = 6.dp) {
                    Row(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ServerProviderSegment(
                            "Emby",
                            MediaServerKind.Emby,
                            form.kind,
                            Modifier.weight(1f),
                            enabled = !form.submitting,
                            outlined = true,
                        ) {
                            sendIntent(ServersIntent.ProviderChanged(MediaServerKind.Emby))
                        }
                        ServerProviderSegment(
                            "Jellyfin",
                            MediaServerKind.Jellyfin,
                            form.kind,
                            Modifier.weight(1f),
                            enabled = !form.submitting,
                            outlined = true,
                        ) {
                            sendIntent(ServersIntent.ProviderChanged(MediaServerKind.Jellyfin))
                        }
                        ServerProviderSegment(
                            "Plex",
                            MediaServerKind.Plex,
                            form.kind,
                            Modifier.weight(1f),
                            enabled = !form.submitting,
                            outlined = true,
                        ) {
                            sendIntent(ServersIntent.ProviderChanged(MediaServerKind.Plex))
                        }
                    }
                }
                ServerFormRow(label = "协议", divider = false, labelBottomPadding = 6.dp) {
                    Row(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ServerProtocolSegment(
                            "HTTPS",
                            form.https,
                            Modifier.weight(1f),
                            enabled = !form.submitting,
                            outlined = true,
                        ) {
                            sendIntent(ServersIntent.ProtocolChanged(true))
                        }
                        ServerProtocolSegment(
                            "HTTP",
                            !form.https,
                            Modifier.weight(1f),
                            enabled = !form.submitting,
                            outlined = true,
                        ) {
                            sendIntent(ServersIntent.ProtocolChanged(false))
                        }
                    }
                }
                ServerFormInput(
                    outlined = true,
                    label = "地址",
                    value = form.host,
                    placeholder = "media.example.com",
                    enabled = !form.submitting,
                    keyboardType = KeyboardType.Uri,
                    divider = false,
                ) { sendIntent(ServersIntent.HostChanged(it)) }
                ServerFormInput(
                    outlined = true,
                    label = "端口",
                    value = form.port,
                    enabled = !form.submitting,
                    keyboardType = KeyboardType.Number,
                    divider = false,
                ) { sendIntent(ServersIntent.PortChanged(it)) }
                // Last rather than first: it is optional, and it used to stand between the user
                // and the address, which is what actually connects.
                ServerFormInput(
                    outlined = true,
                    label = "显示名称",
                    value = form.serverName,
                    placeholder = if (editing) "输入服务器名称" else "留空使用服务器名称",
                    enabled = !form.submitting,
                    divider = false,
                ) { sendIntent(ServersIntent.ServerNameChanged(it)) }
            }
            Spacer(Modifier.height(8.dp))
            FieldLabel("账号")
            ServerFormSection {
                if (form.kind == MediaServerKind.Plex) {
                    ServerFormRow(label = "Plex 云账号", divider = false) {
                        Column(
                            Modifier.motionAwareAnimateContentSize().contentHandoff(state.plexAccount::class),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PlexAccountStage(state.plexAccount)
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
                                    ServerFormButton(
                                        label = if (account == PlexAccountUiState.Idle) "使用 Plex 账号登录" else "重新登录",
                                        onClick = { sendIntent(ServersIntent.StartPlexAccountSignIn) },
                                        modifier = Modifier.fillMaxWidth(),
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
                                        ServerFormButton(
                                            label = "打开 Plex 授权页",
                                            onClick = { uriHandler.openUri(account.authUrl) },
                                            modifier = Modifier.weight(1f),
                                        )
                                        ServerFormButton(
                                            label = "取消",
                                            onClick = { sendIntent(ServersIntent.CancelPlexAccountSignIn) },
                                            modifier = Modifier.weight(1f),
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
                                            outlined = true,
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
                        outlined = true,
                        label = "手动 Plex Token（备用）",
                        value = form.password,
                        placeholder =
                            when {
                                state.reauthenticating -> "输入新的 X-Plex-Token"
                                editing -> "修改连接时重新填写 Token"
                                else -> "输入 X-Plex-Token"
                            },
                        enabled = !form.submitting,
                        password = true,
                        divider = false,
                        onSubmit = submit,
                    ) { sendIntent(ServersIntent.PasswordChanged(it)) }
                } else {
                    ServerFormInput(
                        outlined = true,
                        label = "用户名",
                        value = form.username,
                        placeholder = "输入用户名",
                        enabled = !form.submitting,
                        divider = false,
                        autofillType = ContentType.Username,
                    ) { sendIntent(ServersIntent.UsernameChanged(it)) }
                    ServerFormInput(
                        outlined = true,
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
                        divider = false,
                        autofillType = ContentType.Password,
                        onSubmit = submit,
                        autoFocus = state.reauthenticating,
                    ) { sendIntent(ServersIntent.PasswordChanged(it)) }
                }
            }

            // Offer any public users already loaded for the current server.
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
                            ServerProtocolSegment(
                                outlined = true,
                                label = name,
                                selected = name == form.username,
                                modifier = Modifier.weight(1f),
                                enabled = !form.submitting,
                            ) { sendIntent(ServersIntent.SelectPublicUser(name)) }
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

        ServerFormButton(
            label =
                when {
                    state.reauthenticating -> "重新登录"
                    editing -> "保存修改"
                    else -> "连接到服务器"
                },
            onClick = submit,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            primary = true,
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
            .pressable(role = Role.Button, focusShape = AppShapes.control, onClick = onClick)
            .touchTarget()
            .background(if (palette.isDark) palette.reducedFill.card3 else Color.White, AppShapes.control)
            .border(1.dp, palette.sub2.copy(alpha = 0.75f), AppShapes.control)
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

/** Section labels sit outside the non-interactive grouping surface. */
@Composable
private fun FieldLabel(text: String) {
    Text(text, style = AppTypography.body.strong, color = LocalPalette.current.text)
}

@Composable
private fun ServerFormSection(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(palette.reducedFill.card2, AppShapes.card)
                .border(1.dp, palette.text.copy(alpha = 0.1f), AppShapes.card)
                .padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun ServerFormButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val fill =
        if (primary) {
            accent.accent
        } else if (palette.isDark) {
            palette.reducedFill.card3
        } else {
            Color.White
        }
    val ink = if (primary) accent.onAccent else accent.accent
    // Keep the button's identity while a request runs, and expose its busy state to TalkBack.
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 48.dp).semantics { if (loading) stateDescription = "处理中" },
        enabled = enabled && !loading,
        shape = AppShapes.control,
        border = if (primary) null else BorderStroke(1.dp, accent.accent),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = fill,
                contentColor = ink,
                disabledContainerColor = if (loading) fill else palette.mutedControl,
                disabledContentColor = if (loading) ink else palette.text.copy(alpha = 0.55f),
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (loading) {
            OrbProgress(size = 16.dp, color = ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = AppTypography.body.strong,
            color =
                if (enabled ||
                    loading
                ) {
                    ink
                } else {
                    palette.text.copy(alpha = 0.55f)
                },
        )
    }
}
