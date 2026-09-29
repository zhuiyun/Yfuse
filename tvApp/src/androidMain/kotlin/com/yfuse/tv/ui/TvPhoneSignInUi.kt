package com.yfuse.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.remote.RemoteSignInRequest
import com.yfuse.core.remote.toAuthedServer
import com.yfuse.deviceModel
import com.yfuse.feature.servers.ServersIntent
import com.yfuse.tv.focus.requestFocusWhenAttached
import com.yfuse.tv.focus.tvFocusScope
import com.yfuse.tv.remote.TvPhoneRemote
import com.yfuse.watch.protocol.RemoteSignInServer
import com.yfuse.watch.protocol.WatchProtocol
import kotlinx.coroutines.flow.MutableStateFlow

private const val SIGN_IN_SCOPE = "server-dialog:phone-sign-in"

/** 用手机登录 as 添加服务器 reads it: whether it can be offered now, and where it stands. */
internal data class TvPhoneSignInStatus(
    val available: Boolean,
    val request: RemoteSignInRequest,
)

private val Unavailable = MutableStateFlow(false)
private val NotAsked = MutableStateFlow<RemoteSignInRequest>(RemoteSignInRequest.Idle)

@Composable
internal fun rememberTvPhoneSignInStatus(remote: TvPhoneRemote?): TvPhoneSignInStatus {
    val available by (remote?.signInAvailable ?: Unavailable).collectAsState()
    val request by (remote?.signIn ?: NotAsked).collectAsState()
    return TvPhoneSignInStatus(available, request)
}

/**
 * What 添加服务器 does with a server a phone handed over: signs in with it through [send], the
 * form's own way to a server, local network permission and all. One this television cannot use
 * is answered at once. Leaving 添加服务器 stops asking; nothing a phone offered is kept.
 */
@Composable
internal fun TvPhoneSignInReceiver(
    remote: TvPhoneRemote?,
    available: Boolean,
    send: (ServersIntent) -> Unit,
) {
    val currentSend by rememberUpdatedState(send)
    // Hosting may start after the form opened; a session only arrives while it lasts.
    LaunchedEffect(remote, available) {
        if (remote == null || !available) return@LaunchedEffect
        remote.handedServers.collect { handed ->
            val server = handed.toAuthedServer()
            if (server == null) {
                remote.finishSignIn(saved = false)
            } else {
                currentSend(ServersIntent.SignInWithSession(server))
            }
        }
    }
    DisposableEffect(remote) {
        onDispose { remote?.cancelSignIn() }
    }
}

/**
 * 用手机登录, over 添加服务器 while it runs: what to do on the phone; then which server a phone
 * offers, shown here before that phone may send anything secret; then the sign-in with it. 取消
 * and Back leave it for the form, except once the session has arrived: that ends by itself, and
 * the phone hears how.
 */
@Composable
internal fun TvPhoneSignInDialog(
    request: RemoteSignInRequest,
    focusMemory: TvUiFocusMemory,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val receiving = request is RemoteSignInRequest.Receiving
    GlassDialog(
        onDismiss = onCancel,
        maxWidth = 760.dp,
        contentPadding = 28.dp,
        dismissEnabled = !receiving,
    ) {
        val cancel = overlayDismiss(onCancel)
        val cancelRequester = remember { FocusRequester() }
        val retryRequester = remember { FocusRequester() }
        val expired = request == RemoteSignInRequest.Expired
        // On the way out — or, once the ask lapsed, on asking again. 取消 is one button from the
        // first step to the last, so focus stays put as a phone offers, withdraws and sends.
        LaunchedEffect(expired) { (if (expired) retryRequester else cancelRequester).requestFocusWhenAttached() }
        Column(
            Modifier.fillMaxWidth().tvFocusScope(trapFocus = true),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Text("用手机登录", color = TvOnSurface, fontSize = TvType.section, fontWeight = FontWeight.ExtraBold)
            when (request) {
                RemoteSignInRequest.Waiting -> TvPhoneSignInWaiting()
                is RemoteSignInRequest.Offered -> TvPhoneSignInOffered(request)
                is RemoteSignInRequest.Receiving -> TvPhoneSignInReceiving(request.server)
                RemoteSignInRequest.Expired -> TvPhoneSignInExpired()
                RemoteSignInRequest.Idle -> Unit
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                if (expired) {
                    TvActionButton(
                        label = "返回",
                        stableId = "$SIGN_IN_SCOPE:back",
                        focusScope = SIGN_IN_SCOPE,
                        focusMemory = focusMemory,
                        onClick = cancel,
                        modifier = Modifier.width(132.dp),
                    )
                    TvActionButton(
                        label = "再试一次",
                        stableId = "$SIGN_IN_SCOPE:retry",
                        focusScope = SIGN_IN_SCOPE,
                        focusMemory = focusMemory,
                        onClick = onRetry,
                        primary = true,
                        focusRequester = retryRequester,
                    )
                } else {
                    TvActionButton(
                        label = "取消",
                        stableId = "$SIGN_IN_SCOPE:cancel",
                        focusScope = SIGN_IN_SCOPE,
                        focusMemory = focusMemory,
                        onClick = cancel,
                        modifier = Modifier.width(132.dp),
                        enabled = !receiving,
                        focusRequester = cancelRequester,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvPhoneSignInWaiting() {
    // The name 设备接力 lists this television by.
    val television = remember { deviceModel().take(TELEVISION_NAME_CHARS) }
    TvPhoneSignInStatusLine("等待手机…", waiting = true)
    Text(
        "在登录了同一鱼服账号的手机上打开「我的 → 设备接力」，选择「登录服务器到 $television」，" +
            "再选一台 Emby 或 Jellyfin 服务器。",
        color = TvOnSurface,
        fontSize = TvType.body,
    )
    Text(
        "那台服务器会先显示在这里，你在手机上确认后才会发送。电视收到的是服务器地址和登录会话，不含密码。",
        color = TvOnSurfaceMuted,
        fontSize = TvType.caption,
    )
}

@Composable
private fun TvPhoneSignInOffered(offer: RemoteSignInRequest.Offered) {
    TvPhoneSignInStatusLine(
        offer.phoneName?.let { "「$it」要发送这台服务器：" } ?: "一部手机要发送这台服务器：",
        waiting = false,
    )
    TvHandedServerCard(offer.server)
    Text(
        "核对无误后，在手机上点「发送」。不是这台服务器？在手机上换一台，或选「取消」。",
        color = TvOnSurfaceMuted,
        fontSize = TvType.caption,
    )
}

@Composable
private fun TvPhoneSignInReceiving(server: RemoteSignInServer) {
    TvPhoneSignInStatusLine("正在连接「${server.serverName}」…", waiting = true)
    TvHandedServerCard(server)
    Text(
        "服务器接受这份登录后会自动保存，手机上也会显示结果。",
        color = TvOnSurfaceMuted,
        fontSize = TvType.caption,
    )
}

@Composable
private fun TvPhoneSignInExpired() {
    Text(
        "没有手机发来服务器",
        color = TvWarning,
        fontSize = TvType.body,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.liveStatus(),
    )
    Text(
        "电视等了 ${WatchProtocol.REMOTE_SIGN_IN_ASK_MS / 60_000} 分钟。可以再试一次，或返回填写服务器地址。",
        color = TvOnSurfaceMuted,
        fontSize = TvType.caption,
    )
}

/** Where 用手机登录 stands, said to a screen reader as it changes; the dot while it waits on something. */
@Composable
private fun TvPhoneSignInStatusLine(
    text: String,
    waiting: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (waiting) {
            TvLoadingDot()
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text,
            color = TvOnSurface,
            fontSize = TvType.body,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.liveStatus(),
        )
    }
}

/** The server as the phone offered it — no session in it — laid out as a card on 服务器 is. */
@Composable
private fun TvHandedServerCard(server: RemoteSignInServer) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TvSurface)
            .border(TvFocusMotion.restBorder, TvHairline, shape)
            .padding(19.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            server.serverName,
            color = TvOnSurface,
            fontSize = TvType.section,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${server.kind} · ${server.userName}",
            color = TvOnSurfaceMuted,
            fontSize = TvType.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            server.baseUrl,
            color = TvOnSurfaceMuted,
            fontSize = TvType.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** As the handoff controller cuts this device's name for its heartbeat. */
private const val TELEVISION_NAME_CHARS = 64
