package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.motionAwareItem
import com.yfuse.core.designsystem.continuousRounded
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.WatchAvatar
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
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

/**
 * How much of the right edge the panel takes.
 *
 * Named because the player has to know: anything that floats up that corner has to be moved
 * clear of the panel while it is open, or it plays out behind it — see [WatchReactionOverlay].
 */
internal val WatchChatPanelWidth = 340.dp

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

    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isEmpty()) return@LaunchedEffect
        // Follow the transcript only for someone already at the end of it. Scrolling back
        // through what was said and being thrown to the bottom because somebody typed is not
        // a list that scrolls badly — it is a list that undoes the scroll — and with a room
        // that is talking it happens every few seconds.
        //
        // Nothing visible yet means the panel has just opened, which is exactly when the
        // bottom is where the reader wants to be.
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        when {
            last == null -> listState.scrollToItem(messages.lastIndex)
            last.index >= messages.lastIndex - 1 -> listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty() || !sendingEnabled) return
        if (onSend(text)) draft = ""
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).noRippleClickable(onDismiss))
    Column(
        modifier
            .fillMaxHeight()
            .width(WatchChatPanelWidth)
            .glass(
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                fill = PlayerTokens.drawerFillLandscape,
                border = Color.White.copy(alpha = 0.24f),
            )
            .noRippleClickable { }
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("房间聊天", style = sc(13.5f, 700), color = Color.White)
                Text(
                    "${participants.size} 人在线 · ${participants.count { it.ready }} 人就绪 · 最近 50 条",
                    style = mr(9.5f, 500),
                    color = Color.White.copy(alpha = 0.48f),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (danmakuEnabled) "弹幕开" else "弹幕关",
                    style = sc(9.5f, 650),
                    color = if (danmakuEnabled) Color.White else Color.White.copy(alpha = 0.48f),
                    modifier = Modifier
                        .pressable(onClick = onToggleDanmaku)
                        .glass(
                            continuousRounded(10.dp),
                            if (danmakuEnabled) {
                                Brand.Primary.copy(alpha = 0.45f)
                            } else {
                                Color.White.copy(alpha = 0.08f)
                            },
                            Color.White.copy(alpha = 0.16f),
                        )
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                )
                Icon(
                    AppIcons.Close,
                    contentDescription = "关闭聊天",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(13.dp).noRippleClickable(onDismiss),
                )
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
                            style = mr(8.5f, 500),
                            color = Color.White.copy(alpha = 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(58.dp),
                        )
                        Text(
                            participant.playbackStatusLabel,
                            style = mr(7.5f, 500),
                            color = when {
                                !participant.mediaAvailable -> Brand.Danger
                                participant.buffering -> Color(0xFFFFC857)
                                participant.ready -> Brand.Primary
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
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有消息\n发一句开始聊天吧",
                    style = mr(11f, 500),
                    color = Color.White.copy(alpha = 0.42f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    // Someone else's message arriving mid-film should not be a jump cut.
                    WatchChatBubble(message, onRetry, motionAwareItem())
                }
            }
        }

        error?.let {
            Text(
                it,
                style = mr(9.5f, 500),
                color = Brand.Danger,
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
                        continuousRounded(14.dp),
                        Color.White.copy(alpha = 0.1f),
                        Color.White.copy(alpha = 0.18f),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) {
                        Text(
                            if (sendingEnabled) "说点什么…" else "重连后可发送",
                            style = mr(11f, 500),
                            color = Color.White.copy(alpha = 0.35f),
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { value ->
                            draft = value.replace('\r', ' ')
                                .replace('\n', ' ')
                                .withoutControlCharacters()
                                .takeGraphemes(MAX_CHAT_GRAPHEMES)
                                .takeGraphemesWithinUtf8Bytes(MAX_CHAT_BYTES)
                            onClearError()
                        },
                        singleLine = true,
                        enabled = sendingEnabled,
                        textStyle = mr(11f, 500).copy(color = Color.White),
                        cursorBrush = SolidColor(Brand.Primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "${draft.graphemeCount()}/$MAX_CHAT_GRAPHEMES",
                    style = mr(8f, 500),
                    color = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.End),
                )
            }
            Text(
                "发送",
                style = sc(10.5f, 700),
                color = if (draft.isBlank() || !sendingEnabled) {
                    Color.White.copy(alpha = 0.3f)
                } else {
                    Color.White
                },
                modifier = Modifier
                    .pressable(enabled = draft.isNotBlank() && sendingEnabled, onClick = ::submit)
                    .glass(
                        continuousRounded(13.dp),
                        Brand.Primary.copy(
                            alpha = if (draft.isBlank() || !sendingEnabled) 0.16f else 0.55f,
                        ),
                        Color.White.copy(alpha = 0.2f),
                    )
                    .padding(horizontal = 13.dp, vertical = 13.dp),
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
                    style = mr(8.5f, 600),
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
                        style = mr(11f, 500),
                        color = Color.White.copy(alpha = 0.94f),
                        modifier = Modifier
                            .glass(
                                continuousRounded(12.dp),
                                if (message.isMine) {
                                    Brand.Primary.copy(alpha = 0.48f)
                                } else {
                                    Color.White.copy(alpha = 0.1f)
                                },
                                Color.White.copy(alpha = 0.16f),
                            )
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                    )
                }
                if (message.deliveryState != ChatDeliveryState.Sent) {
                    Text(
                        if (message.deliveryState == ChatDeliveryState.Pending) {
                            "发送中…"
                        } else {
                            "发送失败 · 点击重试"
                        },
                        style = mr(8f, 500),
                        color = if (message.deliveryState == ChatDeliveryState.Failed) {
                            Brand.Danger
                        } else {
                            Color.White.copy(alpha = 0.42f)
                        },
                        modifier = Modifier
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
                continuousRounded(16.dp),
                Color.Black.copy(alpha = 0.58f),
                Color.White.copy(alpha = 0.2f),
            )
            .noRippleClickable(onOpen)
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
                    style = mr(10f, 500),
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
