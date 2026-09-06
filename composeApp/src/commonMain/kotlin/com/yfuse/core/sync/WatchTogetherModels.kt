package com.yfuse.core.sync

import com.yfuse.watch.protocol.WatchProtocol
import kotlin.math.abs

enum class WatchReaction(
    val emoji: String,
) {
    Laugh("😂"),
    Wow("😮"),
    Love("😍"),
    Cry("😭"),
    Clap("👏"),
    Fire("🔥"),
    Think("🤔"),
    Dead("💀"),
    ;

    companion object {
        fun fromWire(value: String?): WatchReaction? = entries.firstOrNull { it.emoji == value }
    }
}

data class WatchReactionBurst(
    val id: Long,
    val reaction: WatchReaction,
    val name: String,
    val isMine: Boolean,
)

data class ControlRequest(
    val clientId: String,
    val name: String,
)

enum class WatchControlMode(
    val wireValue: String,
    val label: String,
) {
    HostOnly("hostOnly", "仅房主"),
    Everyone("everyone", "共同控制"),
    Moderators("moderators", "指定管理员"),
    ;

    companion object {
        fun fromWire(value: String?): WatchControlMode = entries.firstOrNull { it.wireValue == value } ?: HostOnly
    }
}

enum class ChatDeliveryState {
    Sent,
    Pending,
    Failed,
}

enum class WatchNetworkQuality(
    val label: String,
) {
    Excellent("网络优"),
    Fair("网络一般"),
    Poor("网络较差"),
    Unknown("检测中"),
}

data class WatchParticipant(
    val clientId: String,
    val name: String,
    val avatarId: Int,
    val isHost: Boolean,
    val isSelf: Boolean,
    val statusKnown: Boolean = false,
    val ready: Boolean = false,
    val buffering: Boolean = false,
    val mediaAvailable: Boolean = true,
    val latencyMs: Long? = null,
    val syncDriftMs: Long? = null,
    /** The member's local media length, when reported; lets the host spot a different cut. */
    val durationMs: Long? = null,
    val canControl: Boolean = false,
    val isModerator: Boolean = false,
) {
    val playbackStatusLabel: String
        get() =
            when {
                !statusKnown -> "状态未知"
                !mediaAvailable -> "缺少影片"
                buffering -> "缓冲中"
                ready -> "已就绪"
                else -> "准备中"
            }

    val networkQuality: WatchNetworkQuality
        get() {
            val latency = latencyMs ?: return WatchNetworkQuality.Unknown
            val drift = abs(syncDriftMs ?: 0L)
            return when {
                buffering -> WatchNetworkQuality.Poor
                latency <= 120L && drift <= 300L -> WatchNetworkQuality.Excellent
                latency <= 350L && drift <= 1_000L -> WatchNetworkQuality.Fair
                else -> WatchNetworkQuality.Poor
            }
        }

    val networkStatusLabel: String
        get() {
            val latency = latencyMs ?: return WatchNetworkQuality.Unknown.label
            val drift = syncDriftMs?.let { " · 偏差${abs(it)}ms" }.orEmpty()
            return "${networkQuality.label} · ${latency}ms$drift"
        }
}

data class WatchChatMessage(
    val id: Long,
    val clientId: String,
    val name: String,
    val avatarId: Int,
    val text: String,
    val sentAtMs: Long,
    val isMine: Boolean,
    val clientMessageId: String? = null,
    val deliveryState: ChatDeliveryState = ChatDeliveryState.Sent,
)

internal fun mergeIncomingWatchChat(
    messages: List<WatchChatMessage>,
    incoming: WatchChatMessage,
    maxHistory: Int,
): List<WatchChatMessage> {
    val withoutOptimistic =
        incoming.clientMessageId?.let { messageId ->
            messages.filterNot {
                it.clientId == incoming.clientId &&
                    it.clientMessageId == messageId &&
                    it.deliveryState != ChatDeliveryState.Sent
            }
        } ?: messages
    if (withoutOptimistic.any { it.id == incoming.id }) {
        return if (withoutOptimistic.size == messages.size) messages else withoutOptimistic
    }
    return (withoutOptimistic + incoming).takeLast(maxHistory)
}

data class WatchTogetherState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val roomCode: String? = null,
    val isHost: Boolean = false,
    val canControl: Boolean = false,
    val controlMode: WatchControlMode = WatchControlMode.HostOnly,
    val participantCount: Int = 0,
    val participants: List<WatchParticipant> = emptyList(),
    val chatMessages: List<WatchChatMessage> = emptyList(),
    val chatError: String? = null,
    val reactionsSupported: Boolean = false,
    val reactions: List<WatchReactionBurst> = emptyList(),
    val mediaKey: String? = null,
    val error: String? = null,
    val syncWarning: String? = null,
    val localMediaAvailable: Boolean = true,
    val controlRequest: ControlRequest? = null,
    val controlRequested: Boolean = false,
)

internal const val WATCH_CAPABILITY_REACTIONS = WatchProtocol.CAPABILITY_REACTIONS

/** Members whose file differs from this device's by more than this are flagged as a different cut. */
internal const val WATCH_DURATION_MISMATCH_MS = 60_000L

/**
 * Names members whose reported media length differs materially from this device's, so the
 * host can explain a guest that keeps landing at the end of a shorter file.
 */
fun WatchTogetherState.durationMismatchWarning(): String? {
    val self = participants.firstOrNull { it.isSelf }?.durationMs ?: return null
    val mismatched =
        participants.filter { member ->
            !member.isSelf &&
                member.durationMs?.let { abs(it - self) >= WATCH_DURATION_MISMATCH_MS } == true
        }
    if (mismatched.isEmpty()) return null
    val names = mismatched.take(3).joinToString("、") { it.name }
    val suffix = if (mismatched.size > 3) "等 ${mismatched.size} 人" else ""
    return "$names$suffix 的影片时长与你不同，可能是不同版本，同步位置会有偏差"
}

internal fun supportsWatchReactions(capabilities: List<String>?): Boolean =
    WATCH_CAPABILITY_REACTIONS in capabilities.orEmpty()

internal fun WatchTogetherState.canSendReaction(): Boolean = connected && !reconnecting && reactionsSupported

internal data class WatchChatValidation(
    val text: String,
    val error: String?,
)

internal fun validateWatchChat(raw: String): WatchChatValidation {
    val text = raw.trim()
    val error =
        when {
            text.isEmpty() -> "请输入消息"
            !WatchProtocol.isValidChat(text) && text.encodeToByteArray().size > MAX_WATCH_CHAT_BYTES ->
                "消息内容过长"
            !WatchProtocol.isValidChat(text) ->
                "每条消息最多 $MAX_WATCH_CHAT_GRAPHEMES 字"
            else -> null
        }
    return WatchChatValidation(text, error)
}

data class WatchTimeline(
    val mediaKey: String,
    val anchorPositionMs: Long,
    val anchorAtServerMs: Long,
    val rate: Float,
    val paused: Boolean,
    val seq: Long,
) {
    fun expectedPositionMs(serverNowMs: Long): Long {
        if (paused) return anchorPositionMs
        val elapsedMs = (serverNowMs - anchorAtServerMs).coerceAtLeast(0L)
        return anchorPositionMs + (elapsedMs * rate).toLong()
    }
}

internal const val MAX_WATCH_CHAT_GRAPHEMES = WatchProtocol.MAX_CHAT_GRAPHEMES
internal const val MAX_WATCH_CHAT_BYTES = WatchProtocol.MAX_CHAT_BYTES
