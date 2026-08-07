package com.yfuse.core.data

import kotlinx.serialization.Serializable

/** Wire-safe, versioned subset of danmaku preferences that belongs to an account. */
@Serializable
data class DanmakuSyncSnapshot(
    val version: Int = CURRENT_VERSION,
    val sources: List<DanmakuSource> = emptyList(),
    val activeSourceId: String? = null,
    val bindings: Map<String, DanmakuBinding> = emptyMap(),
    val enabled: Boolean = true,
    val displayArea: DanmakuDisplayArea = DanmakuDisplayArea.Half,
    val fontSize: DanmakuFontSize = DanmakuFontSize.Standard,
    val speed: DanmakuSpeed = DanmakuSpeed.Standard,
    val opacity: DanmakuOpacity = DanmakuOpacity.Standard,
    val mergeDuplicates: Boolean = true,
    val blockedWords: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

internal const val MAX_DANMAKU_SYNC_SOURCES: Int = 32
internal const val MAX_DANMAKU_SYNC_SOURCE_ID_CHARS: Int = 128
internal const val MAX_DANMAKU_SYNC_SOURCE_NAME_CHARS: Int = 24
internal const val MAX_DANMAKU_SYNC_SOURCE_URL_CHARS: Int = 4_096
internal const val MAX_DANMAKU_SYNC_BINDINGS: Int = 300
internal const val MAX_DANMAKU_SYNC_BINDING_KEY_CHARS: Int = 512
internal const val MAX_DANMAKU_SYNC_EPISODE_ID_CHARS: Int = 256
internal const val MAX_DANMAKU_SYNC_BINDING_LABEL_CHARS: Int = 120
internal const val MAX_DANMAKU_SYNC_BLOCKED_WORDS: Int = 128
internal const val MAX_DANMAKU_SYNC_BLOCKED_WORD_CHARS: Int = 40
