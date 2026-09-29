package com.yfuse.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.remote.RemoteControlPhone
import com.yfuse.tv.focus.requestFocusWhenAttached
import com.yfuse.tv.focus.tvFocusScope
import com.yfuse.tv.remote.TvPhoneRemote
import com.yfuse.tv.remote.TvPhoneRemoteAnswer
import org.koin.core.context.GlobalContext

private const val PROMPT_SCOPE = "phone-remote-prompt"

/** The question, naming the phone the way it names itself. */
internal fun tvPhoneRemoteQuestion(name: String?): String =
    if (name.isNullOrBlank()) "允许一部手机遥控这台电视？" else "允许「$name」遥控这台电视？"

/**
 * Whether the page this composition belongs to is the one in front: resumed, so neither under
 * another page nor shrunk to picture-in-picture, where no one could answer a question.
 */
@Composable
internal fun rememberTvPageInFront(): Boolean {
    val owner = LocalLifecycleOwner.current
    var state by remember(owner) { mutableStateOf(owner.lifecycle.currentState) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> state = owner.lifecycle.currentState }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state.isAtLeast(Lifecycle.State.RESUMED)
}

/**
 * Asks about the first phone waiting on 手机遥控. The shell holds one and so does the layer over
 * the player; only the page in front asks, so the question is never up twice.
 */
@Composable
internal fun TvPhoneRemotePromptHost(
    remote: TvPhoneRemote,
    focusMemory: TvUiFocusMemory,
) {
    val phones by remote.phones.collectAsState()
    val inFront = rememberTvPageInFront()
    val waiting = phones.firstOrNull { !it.allowed }
    if (!inFront || waiting == null) return
    // A second phone gets a question of its own, not the answer buttons of the first.
    key(waiting.deviceId) {
        TvPhoneRemotePrompt(phone = waiting, focusMemory = focusMemory) { answer -> remote.answer(waiting, answer) }
    }
}

/**
 * Whether [phone] may use 手机遥控 on this television. 拒绝 has focus, and Back answers 拒绝 too:
 * waving the question away would only bring it straight back, and a press made out of habit must
 * never be what lets a phone in. 始终允许此设备 is offered only to a phone that says who it is.
 */
@Composable
internal fun TvPhoneRemotePrompt(
    phone: RemoteControlPhone,
    focusMemory: TvUiFocusMemory,
    onAnswer: (TvPhoneRemoteAnswer) -> Unit,
) {
    val refuseRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { refuseRequester.requestFocusWhenAttached() }
    val answer by rememberUpdatedState(onAnswer)
    GlassDialog(
        onDismiss = { answer(TvPhoneRemoteAnswer.Refuse) },
        maxWidth = 720.dp,
        contentPadding = Dimens.space.xxl,
    ) {
        // Every answer lets the panel finish leaving first, as Back already does.
        val refuse = overlayDismiss { answer(TvPhoneRemoteAnswer.Refuse) }
        val allowOnce = overlayAction { answer(TvPhoneRemoteAnswer.AllowOnce) }
        val allowAlways = overlayAction { answer(TvPhoneRemoteAnswer.AllowAlways) }
        Column(
            Modifier.fillMaxWidth().tvFocusScope(trapFocus = true),
            verticalArrangement = Arrangement.spacedBy(Dimens.space.md),
        ) {
            Text(
                tvPhoneRemoteQuestion(phone.name),
                color = TvOnSurface,
                fontSize = TvType.section,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "这部手机登录了与电视相同的账号。允许后，它可以像遥控器一样操作这台电视，并向搜索框输入文字。",
                color = TvOnSurfaceMuted,
                fontSize = TvType.body,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = Dimens.space.sm),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space.md, Alignment.End),
            ) {
                TvActionButton(
                    label = "拒绝",
                    stableId = "$PROMPT_SCOPE:refuse",
                    focusScope = PROMPT_SCOPE,
                    focusMemory = focusMemory,
                    onClick = refuse,
                    focusRequester = refuseRequester,
                )
                TvActionButton(
                    label = "允许一次",
                    stableId = "$PROMPT_SCOPE:once",
                    focusScope = PROMPT_SCOPE,
                    focusMemory = focusMemory,
                    onClick = allowOnce,
                )
                if (phone.rememberable) {
                    TvActionButton(
                        label = "始终允许此设备",
                        stableId = "$PROMPT_SCOPE:always",
                        focusScope = PROMPT_SCOPE,
                        focusMemory = focusMemory,
                        onClick = allowAlways,
                    )
                }
            }
        }
    }
}

/**
 * What 手机遥控 lays over a page other than the shell — the player — in the television's theme,
 * which the page's own does not reach: 手机遥控中 while a phone is in, and the question about a
 * phone that is waiting. The mark is never focused, so the player keeps every key; 断开 is on the
 * shell and in 设置.
 */
@Composable
internal fun TvPhoneRemoteOverlay(remote: TvPhoneRemote) {
    val preferences = remember { runCatching { GlobalContext.get().get<ThemePreferences>() }.getOrNull() } ?: return
    val focusMemory = remember { TvUiFocusMemory() }
    TvTheme(preferences) {
        val phones by remote.phones.collectAsState()
        val inFront = rememberTvPageInFront()
        val status = tvPhoneRemoteStatus(phones.count { it.allowed })
        Box(Modifier.fillMaxSize()) {
            if (inFront && status != null) {
                TvPhoneRemoteMark(
                    label = status,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = TvSafeVertical, end = TvSafeHorizontal),
                )
            }
        }
        TvPhoneRemotePromptHost(remote, focusMemory)
    }
}

/** 手机遥控中, or how many phones are in; null while none is. */
internal fun tvPhoneRemoteStatus(connected: Int): String? =
    when {
        connected <= 0 -> null
        connected == 1 -> "手机遥控中"
        else -> "$connected 部手机遥控中"
    }

/** The settings root's word on 手机遥控: off, or how many phones are in. */
internal fun tvPhoneRemoteSummary(
    enabled: Boolean,
    connected: Int,
): String =
    when {
        !enabled -> "已关闭"
        connected > 0 -> "$connected 部已连接"
        else -> ""
    }

@Composable
internal fun rememberTvPhoneRemoteSummary(): String {
    val remote = TvPhoneRemote.current ?: return ""
    val enabled by remote.preferences.enabled.collectAsState()
    val phones by remote.phones.collectAsState()
    return tvPhoneRemoteSummary(enabled, phones.count { it.allowed })
}

/**
 * 手机遥控中 · 断开 at the top right of the shell for as long as a phone is in, over whichever page
 * shows. It is a focus stop like any other — Up from the top of a page, or from 首页 on the rail,
 * reaches it — and one press lets every phone go.
 */
@Composable
internal fun TvPhoneRemoteIndicator(
    remote: TvPhoneRemote,
    focusMemory: TvUiFocusMemory,
    modifier: Modifier = Modifier,
) {
    val phones by remote.phones.collectAsState()
    val status = tvPhoneRemoteStatus(phones.count { it.allowed }) ?: return
    val label = "$status · 断开"
    val focusManager = LocalFocusManager.current
    TvFocusableSurface(
        stableId = "phone-remote:disconnect",
        focusScope = "phone-remote",
        focusMemory = focusMemory,
        onClick = {
            // The pill leaves with the phones: focus steps down onto the page, not into nothing.
            focusManager.moveFocus(FocusDirection.Down)
            remote.disconnectAll()
        },
        contentDescription = label,
        modifier = modifier.height(TvPhoneRemoteMarkHeight),
        shape = RoundedCornerShape(TvPhoneRemoteMarkHeight / 2),
        scaleWhenFocused = TvFocusMotion.BUTTON_SCALE,
    ) {
        // White plate and black ink on the focus clock, as on every button of the shell.
        val focus = LocalTvFocusAmount.current
        Row(
            Modifier
                .fillMaxHeight()
                .drawBehind { drawRect(Color.White.copy(alpha = focus.value.coerceIn(0f, 1f))) }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvFocusIcon(icon = AppIcons.Cast, rest = TvAccent, focused = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            TvFocusText(
                text = label,
                rest = TvOnSurface,
                focused = Color.Black,
                fontSize = TvType.caption,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The indicator's look without its button, for a page that must keep every key. */
@Composable
private fun TvPhoneRemoteMark(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(TvPhoneRemoteMarkHeight)
            .clip(RoundedCornerShape(TvPhoneRemoteMarkHeight / 2))
            .background(TvSurface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Cast, contentDescription = null, tint = TvAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = TvOnSurface, fontSize = TvType.caption, fontWeight = FontWeight.SemiBold)
    }
}

private val TvPhoneRemoteMarkHeight = 40.dp

/**
 * 设置 → 手机遥控: the switch, 断开 for the phones in now, and the phones let in for good. Removing
 * one does not disconnect it; the next time it connects, the television asks about it again.
 */
@Composable
internal fun TvPhoneRemoteSettingsPage(
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val remote = TvPhoneRemote.current ?: return
    val enabled by remote.preferences.enabled.collectAsState()
    val trusted by remote.preferences.trusted.collectAsState()
    val phones by remote.phones.collectAsState()
    val connected = phones.count { it.allowed }
    val focusManager = LocalFocusManager.current
    val scope = "settings:phone-remote"

    TvSettingsPageScaffold(page = TvSettingsPage.PhoneRemote) {
        item(key = "phone-remote-enabled") {
            TvToggleRow(
                title = "允许手机遥控",
                checked = enabled,
                stableId = "settings:phone-remote:enabled",
                focusMemory = focusMemory,
                onToggle = remote.preferences::setEnabled,
                icon = AppIcons.Cast,
                focusScope = scope,
                subtitle = "关闭后，手机的遥控列表里不再有这台电视，已连接的手机也会断开",
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }
        if (connected > 0) {
            item(key = "phone-remote-disconnect") {
                TvSettingRow(
                    title = "断开所有手机",
                    value = "$connected 部已连接",
                    stableId = "settings:phone-remote:disconnect",
                    focusMemory = focusMemory,
                    onClick = {
                        // The row leaves with the phones: focus goes up to the switch first.
                        focusManager.moveFocus(FocusDirection.Up)
                        remote.disconnectAll()
                    },
                    icon = AppIcons.Close,
                    focusScope = scope,
                    navigationRequester = navigationRequester,
                )
            }
        }
        item(key = "phone-remote-trusted-title") { TvSettingsSectionTitle("始终允许的手机") }
        if (trusted.isEmpty()) {
            item(key = "phone-remote-trusted-empty") {
                TvSettingsNote("还没有。手机第一次连接时电视会先询问，选择「始终允许此设备」的手机会列在这里。")
            }
        }
        trusted.forEach { phone ->
            item(key = "phone-remote-trusted:${phone.deviceId}") {
                TvSettingRow(
                    title = phone.name ?: "未命名的手机",
                    value = "移除",
                    stableId = "settings:phone-remote:trusted:${phone.deviceId}",
                    focusMemory = focusMemory,
                    onClick = {
                        // As for 断开: the row goes, so focus moves to the one above it first.
                        focusManager.moveFocus(FocusDirection.Up)
                        remote.preferences.forget(phone.deviceId)
                    },
                    icon = AppIcons.Check,
                    focusScope = scope,
                    subtitle = "移除后，它下次连接时电视会重新询问",
                    navigationRequester = navigationRequester,
                )
            }
        }
        item(key = "phone-remote-note") {
            TvSettingsNote("电视打开这个应用时才接受手机遥控；手机遥控也不能向密码框输入文字。")
        }
    }
}
