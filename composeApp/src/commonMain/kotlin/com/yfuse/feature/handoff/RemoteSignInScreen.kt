package com.yfuse.feature.handoff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.Section
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.SettingsDivider
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.remote.RemoteSignInClient
import com.yfuse.core.remote.RemoteSignInState
import com.yfuse.core.remote.toRemoteSignInServer
import com.yfuse.feature.profile.SettingsPage
import org.koin.core.context.GlobalContext

/**
 * 登录服务器到电视 — 用手机登录 from the phone's side, for a television of the same account whose
 * 添加服务器 is waiting. Picking a server puts it before the television without its session, and the
 * television shows it; the session goes only when the person confirms 发送 here, having checked
 * that the television shows the same server. Plex servers are listed, but keep their own sign-in.
 */
@Composable
internal fun RemoteSignInScreen(
    televisionSessionId: String,
    televisionName: String,
    onBack: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val koin = remember { GlobalContext.get() }
    val registry = remember { koin.get<ServerRegistry>() }
    val personal = remember { koin.get<PersonalLibraryRepository>() }
    // The screen's own scope: the hand-over runs on the thread its buttons do, and ends with it.
    val scope = rememberCoroutineScope()
    val client =
        remember {
            val tokens = koin.get<AccountAccessTokenSource>()
            RemoteSignInClient(
                accessToken = { tokens.validAccessTokenFor(ACCOUNT_BASE_URL) },
                refreshAccessToken = { tokens.refreshAccessTokenFor(ACCOUNT_BASE_URL) },
                scope = scope,
            )
        }
    // Leaving withdraws a server that was only offered: the television stops showing it.
    DisposableEffect(client) { onDispose { client.close() } }
    PlatformBackHandler(onBack = onBack)
    val servers by registry.data.collectAsState()
    val access by personal.policy.collectAsState()
    val state by client.state.collectAsState()
    var picked by remember { mutableStateOf<SavedServer?>(null) }
    // Results buzz, picks do not: the television saved it, or it did not.
    LaunchedEffect(client) {
        var last: RemoteSignInState = RemoteSignInState.Idle
        client.state.collect { now ->
            if (now == RemoteSignInState.Done && last != RemoteSignInState.Done) haptics.play(HapticSignal.Confirm)
            if (now is RemoteSignInState.Failed && last !is RemoteSignInState.Failed) haptics.play(HapticSignal.Reject)
            last = now
        }
    }
    val busy = state == RemoteSignInState.Offering || state == RemoteSignInState.Sending
    val offer: (SavedServer) -> Unit = { server ->
        if (!busy && client.offer(televisionSessionId, server)) picked = server
    }

    SettingsPage(title = "登录服务器到电视", subtitle = televisionName, onBack = onBack) {
        item {
            Section(title = "选择服务器") {
                SettingsCard {
                    when {
                        // A child profile may not hand out what it may not change.
                        !access.canManageServers ->
                            SettingRow("当前资料不能发送服务器登录", "请切换到成人资料", embedded = true)
                        servers.servers.isEmpty() -> SettingRow("这部手机还没有服务器", embedded = true)
                        else ->
                            servers.servers.forEachIndexed { index, server ->
                                if (index > 0) SettingsDivider()
                                RemoteSignInServerRow(
                                    server = server,
                                    loading = busy && picked?.id == server.id,
                                    enabled = !busy && state != RemoteSignInState.Done,
                                    onClick = { offer(server) },
                                )
                            }
                    }
                }
            }
        }
        remoteSignInStatus(
            state = state,
            server = picked,
            televisionName = televisionName,
            onRetry = { picked?.let(offer) },
            onDone = onBack,
        )
        item {
            Section(title = "说明") {
                ThemeText(
                    "电视会先显示你选的服务器，核对一致后在这里确认发送。电视收到的是这部手机保存的服务器地址和登录会话，" +
                        "不包含密码。Plex 服务器不能这样发送，请在电视上直接登录。",
                    style = AppTypography.caption.regular.copy(lineHeight = 17.sp),
                    color = LocalPalette.current.sub2,
                )
            }
        }
    }
    // The one explicit step: nothing secret leaves this phone before 发送.
    DialogPresence(picked?.takeIf { state == RemoteSignInState.Confirming }) { server ->
        RemoteSignInConfirmDialog(
            server = server,
            televisionName = televisionName,
            onSend = client::send,
            onCancel = {
                client.close()
                picked = null
            },
        )
    }
}

@Composable
private fun RemoteSignInServerRow(
    server: SavedServer,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Plex keeps its own sign-in; a server whose details the relay would refuse is not offered.
    val handable = server.toRemoteSignInServer() != null
    SettingRow(
        title = server.serverName,
        supporting =
            when {
                server.kind == MediaServerKind.Plex -> "Plex 不能用手机登录，请在电视上直接登录"
                !handable -> "这台服务器的信息无法发送到电视"
                else -> "${server.kind.name} · ${server.userName}"
            },
        embedded = true,
        icon = AppIcons.Server,
        enabled = enabled && handable,
        loading = loading,
        onClick = onClick.takeIf { handable },
    )
}

/** Where the hand-over stands, read out as it changes; nothing until a server is picked. */
private fun LazyListScope.remoteSignInStatus(
    state: RemoteSignInState,
    server: SavedServer?,
    televisionName: String,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    val name = server?.serverName.orEmpty()
    val (message, failed) =
        when (state) {
            RemoteSignInState.Offering -> "正在发送到电视…" to false
            RemoteSignInState.Confirming -> "请核对电视上显示的服务器" to false
            RemoteSignInState.Sending -> "「$televisionName」正在连接「$name」…" to false
            RemoteSignInState.Done -> "「$televisionName」已登录「$name」" to false
            is RemoteSignInState.Failed -> state.message to true
            RemoteSignInState.Idle -> return
        }
    val action =
        when {
            state == RemoteSignInState.Done -> "完成" to onDone
            state is RemoteSignInState.Failed && state.retryable && server != null -> "重试" to onRetry
            else -> null
        }
    item {
        val palette = LocalPalette.current
        ThemeText(
            message,
            style = AppTypography.body.medium,
            color = if (failed) palette.error else palette.body,
            modifier =
                Modifier
                    .padding(horizontal = Dimens.pageHorizontal)
                    .liveStatus(assertive = failed),
        )
    }
    action?.let { (label, onClick) ->
        item {
            SettingsCard {
                SettingRow(label, embedded = true, onClick = onClick)
            }
        }
    }
}

/**
 * 发送到电视？ The television shows the same server by now; the session goes only from here. The
 * scrim and back mean 取消, which withdraws the server from the television's screen as well.
 */
@Composable
private fun RemoteSignInConfirmDialog(
    server: SavedServer,
    televisionName: String,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalPalette.current
    GlassDialog(onDismiss = onCancel) {
        val cancel = overlayDismiss(onCancel)
        val send = overlayAction(onSend)
        ThemeText("发送到「$televisionName」？", style = AppTypography.section.strong, color = palette.text)
        Spacer(Modifier.height(Dimens.space.sm))
        ThemeText(
            "电视上应显示「${server.serverName}」（${server.kind.name} · ${server.userName}）。核对一致后再发送。" +
                "发送的是这部手机保存的登录会话，不包含密码。",
            style = AppTypography.body.regular.copy(lineHeight = 21.sp),
            color = palette.body,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = Dimens.space.lg),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space.md),
        ) {
            OverlayButton("取消", onClick = cancel, modifier = Modifier.weight(1f))
            OverlayButton(
                label = "发送",
                onClick = send,
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
            )
        }
    }
}
