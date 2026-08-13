package com.yfuse.feature.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.WatchAvatar
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.motionAwareItem
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.sync.ChatDeliveryState
import com.yfuse.core.sync.WatchChatMessage
import com.yfuse.core.sync.WatchParticipant
import com.yfuse.core.sync.WatchStickers
import com.yfuse.core.sync.oneLineText
import com.yfuse.core.sync.sticker
import com.yfuse.core.util.graphemeCount
import com.yfuse.core.util.takeGraphemes
import com.yfuse.core.util.takeGraphemesWithinUtf8Bytes
import com.yfuse.core.util.withoutControlCharacters
import kotlinx.coroutines.launch

/**
 * How much of the right edge the panel takes.
 *
 * Named because the player has to know: anything that floats up that corner has to be moved
 * clear of the panel while it is open, or it plays out behind it — see [WatchReactionOverlay].
 */
internal val WatchChatPanelWidth = PlayerPanelWidth

@Composable
internal fun WatchChatPanel(
    participants: List<WatchParticipant>,
    messages: List<WatchChatMessage>,
    error: String?,
    sendingEnabled: Boolean,
    danmakuEnabled: Boolean,
    onSend: (String) -> Boolean,
    onRetry: (String) -> Unit,
    onClearError: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val accent = rememberAccentColorsForSurface(dark = true)
    var showJumpToLatest by remember { mutableStateOf(false) }

    LaunchedEffect(messages.lastOrNull()?.id, reduceMotion) {
        if (messages.isEmpty()) {
            showJumpToLatest = false
            return@LaunchedEffect
        }
        // Follow the transcript only for someone already at the end of it. Scrolling back
        // through what was said and being thrown to the bottom because somebody typed is not
        // a list that scrolls badly — it is a list that undoes the scroll — and with a room
        // that is talking it happens every few seconds.
        //
        // Nothing visible yet means the panel has just opened, which is exactly when the
        // bottom is where the reader wants to be.
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        when {
            last == null -> {
                listState.scrollToItem(messages.lastIndex)
                showJumpToLatest = false
            }
            last.index >= messages.lastIndex - 1 -> {
                if (reduceMotion) {
                    listState.scrollToItem(messages.lastIndex)
                } else {
                    listState.animateScrollToItem(messages.lastIndex)
                }
                showJumpToLatest = false
            }
            else -> showJumpToLatest = true
        }
    }

    LaunchedEffect(listState, messages.lastIndex) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: -1
        }.collect { lastVisibleIndex ->
            if (
                messages.isNotEmpty() &&
                lastVisibleIndex >= messages.lastIndex - 1
            ) {
                showJumpToLatest = false
            }
        }
    }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty() || !sendingEnabled) return
        if (onSend(text)) draft = ""
    }

    // Same drawer as 设置 and 搜索弹幕 — see [PlayerSidePanel].
    PlayerSidePanel(onDismiss = onDismiss, modifier = modifier, dim = true) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("房间聊天", style = AppTypography.body.strong, color = Color.White)
                Text(
                    "${participants.size} 人在线 · ${participants.count { it.ready }} 人就绪 · 最近 50 条",
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.48f),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (danmakuEnabled) "弹幕开" else "弹幕关",
                    style = AppTypography.caption.strong,
                    color = if (danmakuEnabled) accent.accent else Color.White.copy(alpha = 0.48f),
                    modifier =
                        Modifier
                            .pressable(onClick = onToggleDanmaku)
                            .touchTarget()
                            .glass(
                                AppShapes.thumb,
                                if (danmakuEnabled) {
                                    accent.container
                                } else {
                                    Color.White.copy(alpha = 0.08f)
                                },
                                if (danmakuEnabled) accent.border else Color.White.copy(alpha = 0.16f),
                            ).padding(horizontal = 9.dp, vertical = 6.dp),
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .pressable(
                            onClickLabel = "关闭聊天",
                            onClick = onDismiss,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = "关闭聊天",
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (participants.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                participants.forEach { participant ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        WatchAvatar(participant.avatarId, 30.dp)
                        Text(
                            when {
                                participant.isSelf -> "我"
                                participant.isHost -> "${participant.name} · 房主"
                                else -> participant.name
                            },
                            style = AppTypography.caption.medium,
                            color = Color.White.copy(alpha = 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(58.dp),
                        )
                        Text(
                            participant.playbackStatusLabel,
                            style = AppTypography.caption.medium,
                            color =
                                when {
                                    !participant.mediaAvailable -> DarkPalette.error
                                    participant.buffering -> Color(0xFFFFC857)
                                    participant.ready -> accent.accent
                                    else -> Color.White.copy(alpha = 0.42f)
                                },
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // The tray sits above the transcript, where it can be tapped without reading
        // anything first.
        //
        // A key sends an ordinary chat message whose text is the sticker's token — see
        // [WatchStickers]. That is what fixed it: the keys used to fire a reaction that
        // floated up the bottom-right corner of the player, which is precisely where this
        // panel is, so tapping one from in here produced a bubble that rose entirely behind
        // the panel that sent it. It looked like the key did not work. As a message it lands
        // in the transcript and flies past as 弹幕, on both sides, like anything else said
        // in the room.
        WatchStickerTray(
            enabled = sendingEnabled,
            onPick = { onSend(WatchStickers.token(it)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有消息\n发一句开始聊天吧",
                        style = AppTypography.caption.medium,
                        color = Color.White.copy(alpha = 0.42f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        // Someone else's message arriving mid-film should not be a jump cut.
                        WatchChatBubble(message, onRetry, motionAwareItem())
                    }
                }
            }

            if (showJumpToLatest && messages.isNotEmpty()) {
                Text(
                    "有新消息 · 回到最新",
                    style = AppTypography.caption.strong,
                    color = accent.onAccent,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .pressable(
                                onClickLabel = "回到最新消息",
                                onClick = {
                                    scope.launch {
                                        if (reduceMotion) {
                                            listState.scrollToItem(messages.lastIndex)
                                        } else {
                                            listState.animateScrollToItem(messages.lastIndex)
                                        }
                                        showJumpToLatest = false
                                    }
                                },
                            ).touchTarget()
                            .glass(
                                AppShapes.control,
                                accent.accent,
                                accent.border,
                            ).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        error?.let {
            Text(
                it,
                style = AppTypography.caption.medium,
                color = DarkPalette.error,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .glass(
                        AppShapes.control,
                        Color.White.copy(alpha = 0.1f),
                        Color.White.copy(alpha = 0.18f),
                    ).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) {
                        Text(
                            if (sendingEnabled) "说点什么…" else "重连后可发送",
                            style = AppTypography.caption.medium,
                            color = Color.White.copy(alpha = 0.35f),
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { value ->
                            draft =
                                value
                                    .replace('\r', ' ')
                                    .replace('\n', ' ')
                                    .withoutControlCharacters()
                                    .takeGraphemes(MAX_CHAT_GRAPHEMES)
                                    .takeGraphemesWithinUtf8Bytes(MAX_CHAT_BYTES)
                            onClearError()
                        },
                        singleLine = true,
                        enabled = sendingEnabled,
                        textStyle = AppTypography.caption.medium.copy(color = Color.White),
                        cursorBrush = SolidColor(accent.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "${draft.graphemeCount()}/$MAX_CHAT_GRAPHEMES",
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.End),
                )
            }
            Text(
                "发送",
                style = AppTypography.caption.strong,
                color =
                    if (draft.isBlank() || !sendingEnabled) {
                        Color.White.copy(alpha = 0.3f)
                    } else {
                        accent.onAccent
                    },
                modifier =
                    Modifier
                        .pressable(enabled = draft.isNotBlank() && sendingEnabled, onClick = ::submit)
                        .touchTarget()
                        .glass(
                            AppShapes.control,
                            if (draft.isBlank() || !sendingEnabled) accent.container else accent.accent,
                            accent.border.copy(alpha = if (draft.isBlank() || !sendingEnabled) 0.38f else 1f),
                        ).padding(horizontal = 13.dp, vertical = 13.dp),
            )
        }
    }
}

@Composable
private fun WatchChatBubble(
    message: WatchChatMessage,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!message.isMine) WatchAvatar(message.avatarId, 25.dp)
            Column(horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start) {
                Text(
                    if (message.isMine) "我" else message.name,
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.48f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                val sticker = message.sticker
                if (sticker != null) {
                    // No bubble around it. A sticker is the whole message, and a tinted
                    // capsule behind a 40sp glyph reads as a glyph that has been quoted.
                    WatchStickerGlyph(
                        sticker = sticker,
                        sizeSp = 40f,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                } else {
                    Text(
                        message.text,
                        style = AppTypography.caption.medium,
                        color = if (message.isMine) accent.accent else Color.White.copy(alpha = 0.94f),
                        modifier =
                            Modifier
                                .glass(
                                    AppShapes.thumb,
                                    if (message.isMine) {
                                        accent.container
                                    } else {
                                        Color.White.copy(alpha = 0.1f)
                                    },
                                    if (message.isMine) accent.border else Color.White.copy(alpha = 0.16f),
                                ).padding(horizontal = 11.dp, vertical = 8.dp),
                    )
                }
                if (message.deliveryState != ChatDeliveryState.Sent) {
                    Text(
                        if (message.deliveryState == ChatDeliveryState.Pending) {
                            "发送中…"
                        } else {
                            "发送失败 · 点击重试"
                        },
                        style = AppTypography.caption.medium,
                        color =
                            if (message.deliveryState == ChatDeliveryState.Failed) {
                                DarkPalette.error
                            } else {
                                Color.White.copy(alpha = 0.42f)
                            },
                        modifier =
                            Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .noRippleClickable {
                                    if (message.deliveryState == ChatDeliveryState.Failed) {
                                        message.clientMessageId?.let(onRetry)
                                    }
                                },
                    )
                }
            }
        }
    }
}

@Composable
internal fun WatchChatPreview(
    messages: List<WatchChatMessage>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(260.dp)
            .glass(
                AppShapes.card,
                Color.Black.copy(alpha = 0.58f),
                Color.White.copy(alpha = 0.2f),
            ).noRippleClickable(onOpen)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        messages.takeLast(2).forEach { message ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WatchAvatar(message.avatarId, 23.dp)
                Text(
                    "${if (message.isMine) "我" else message.name}  ${message.oneLineText}",
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private const val MAX_CHAT_GRAPHEMES = 30
private const val MAX_CHAT_BYTES = 768
