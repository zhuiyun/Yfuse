package com.yfuse.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.data.ThemePreferences
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
 * which the page's own does not reach: the question about a phone that is waiting.
 */
@Composable
internal fun TvPhoneRemoteOverlay(remote: TvPhoneRemote) {
    val preferences = remember { runCatching { GlobalContext.get().get<ThemePreferences>() }.getOrNull() } ?: return
    val focusMemory = remember { TvUiFocusMemory() }
    TvTheme(preferences) {
        TvPhoneRemotePromptHost(remote, focusMemory)
    }
}
