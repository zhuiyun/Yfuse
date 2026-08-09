package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.continuousRounded
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.WatchAvatar
import com.yfuse.core.designsystem.sc
import com.yfuse.core.sync.WatchChatMessage
import com.yfuse.core.sync.sticker
import kotlin.math.roundToInt

private const val MAX_LANES = 6
private const val MAX_ACTIVE_MESSAGES = 24
private const val SPEED_DP_PER_SECOND = 118f
private val CHAT_LANE_HEIGHT = 36.dp

private data class WatchChatFlight(
    val message: WatchChatMessage,
    val lane: Int,
)

internal sealed interface WatchChatAnimationKey {
    data class ClientMessage(
        val clientId: String,
        val clientMessageId: String,
    ) : WatchChatAnimationKey

    data class ServerMessage(val id: Long) : WatchChatAnimationKey
}

/**
 * Pending local chat and its later server echo deliberately share one key. This lets the
 * sender see the optimistic message immediately without animating it a second time when
 * the echo replaces the pending row in chat history.
 */
internal fun WatchChatMessage.animationKey(): WatchChatAnimationKey = clientMessageId
    ?.let { WatchChatAnimationKey.ClientMessage(clientId, it) }
    ?: WatchChatAnimationKey.ServerMessage(id)

/** Returns only messages whose stable local/server identity has not been animated yet. */
internal fun watchChatMessagesNotSeen(
    messages: List<WatchChatMessage>,
    seenKeys: Set<WatchChatAnimationKey>,
): List<WatchChatMessage> = messages
    .filter { it.animationKey() !in seenKeys }
    .sortedWith(compareBy<WatchChatMessage> { it.sentAtMs }.thenBy { it.id })

/**
 * What is owed a flight right now.
 *
 * Everything said in the room, by anyone, whether or not the chat panel happens to be open
 * over the picture. The panel used to suppress 弹幕 while it was up, on the reasoning that it
 * was already listing the same messages — but the panel is a 340dp drawer and 弹幕 cross the
 * whole width above it, so the two were never competing for the same space, and the room's
 * chat is worth seeing whether or not you are currently reading the transcript of it.
 *
 * @param limit at most one lane each, so a burst does not stack several messages at the same
 *   x in the same lane.
 */
internal fun watchChatDanmakuArrivals(
    messages: List<WatchChatMessage>,
    seenKeys: Set<WatchChatAnimationKey>,
    limit: Int,
): List<WatchChatMessage> = watchChatMessagesNotSeen(messages, seenKeys).takeLast(limit)

/**
 * Real-time room chat overlay. Unlike media danmaku it uses wall-clock animation, so chat
 * remains readable while playback is paused and does not jump when the viewer seeks.
 */
@Composable
internal fun WatchChatDanmakuOverlay(
    roomCode: String?,
    messages: List<WatchChatMessage>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val messageKeys = messages.map { it.animationKey() }
    var seenKeys by remember(roomCode) { mutableStateOf(messageKeys.toSet()) }
    var nextLane by remember(roomCode) { mutableIntStateOf(0) }
    var active by remember(roomCode) { mutableStateOf(emptyList<WatchChatFlight>()) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .fillMaxHeight(0.52f)
            .clipToBounds(),
    ) {
        val laneCount = (maxHeight / CHAT_LANE_HEIGHT).toInt().coerceIn(1, MAX_LANES)

        val activeKeys = active.map { it.message.animationKey() }
        LaunchedEffect(enabled, roomCode, messageKeys, laneCount, activeKeys) {
            if (!enabled || roomCode == null) {
                // Off by the viewer's own choice, or no room to belong to: nothing is owed a
                // flight, now or later.
                seenKeys = messageKeys.toSet()
                active = emptyList()
                return@LaunchedEffect
            }
            // Never put a second message into a lane whose first message is still crossing.
            // When every lane is occupied, leave the message unseen; completion changes
            // activeKeys and this effect schedules the waiting message on the next free lane.
            val occupied = active.mapTo(mutableSetOf()) { it.lane }
            val available = (0 until laneCount)
                .map { (nextLane + it) % laneCount }
                .filterNot(occupied::contains)
            val arrivals = watchChatDanmakuArrivals(messages, seenKeys, available.size)
            if (arrivals.isEmpty()) return@LaunchedEffect
            val flights = arrivals.mapIndexed { index, message ->
                WatchChatFlight(message, available[index])
            }
            // History can be bounded by the relay; discard identities no longer present so
            // a long room session does not turn this into an ever-growing set.
            seenKeys = (seenKeys intersect messageKeys.toSet()) +
                arrivals.map { it.animationKey() }
            nextLane = (flights.last().lane + 1) % laneCount
            active = (active + flights).takeLast(MAX_ACTIVE_MESSAGES)
        }

        active.forEach { flight ->
            val animationKey = flight.message.animationKey()
            key(animationKey) {
                WatchChatDanmakuItem(
                    flight = flight,
                    viewportWidth = maxWidth,
                    onFinished = {
                        active = active.filterNot { it.message.animationKey() == animationKey }
                    },
                )
            }
        }
    }
}

@Composable
private fun WatchChatDanmakuItem(
    flight: WatchChatFlight,
    viewportWidth: androidx.compose.ui.unit.Dp,
    onFinished: () -> Unit,
) {
    val density = LocalDensity.current
    val viewportWidthPx = with(density) { viewportWidth.toPx() }
    val animationKey = flight.message.animationKey()
    val offsetX = remember(animationKey) { Animatable(viewportWidthPx) }
    val latestOnFinished by rememberUpdatedState(onFinished)
    var contentWidthPx by remember(animationKey) { mutableIntStateOf(0) }
    var ready by remember(animationKey) { mutableStateOf(false) }

    LaunchedEffect(viewportWidthPx, contentWidthPx) {
        if (viewportWidthPx <= 0f || contentWidthPx <= 0) return@LaunchedEffect
        offsetX.snapTo(viewportWidthPx)
        ready = true
        val durationMs = (
            (viewportWidthPx + contentWidthPx) / density.density / SPEED_DP_PER_SECOND * 1_000f
            ).roundToInt().coerceIn(5_500, 11_000)
        offsetX.animateTo(
            targetValue = -contentWidthPx.toFloat(),
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
        )
        latestOnFinished()
    }

    Row(
        Modifier
            .offset(y = 8.dp + CHAT_LANE_HEIGHT * flight.lane)
            .graphicsLayer {
                translationX = offsetX.value
                alpha = if (ready) 1f else 0f
            }
            .onSizeChanged { contentWidthPx = it.width }
            .background(
                color = if (flight.message.isMine) {
                    Brand.Primary.copy(alpha = 0.72f)
                } else {
                    Color.Black.copy(alpha = 0.66f)
                },
                shape = continuousRounded(18.dp),
            )
            .padding(start = 5.dp, end = 11.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WatchAvatar(flight.message.avatarId, 22.dp)
        val sticker = flight.message.sticker
        Text(
            text = if (sticker == null) {
                "${flight.message.name}：${flight.message.text}"
            } else {
                flight.message.name
            },
            style = sc(11f, 650),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        // Drawn rather than spelled: a sticker crossing the picture is the point of sending
        // one, and "张三：[sticker:party]" is the point being missed.
        if (sticker != null) WatchStickerGlyph(sticker, sizeSp = 19f)
    }
}
