package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaSourceHints
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage

/**
 * Shared vocabulary for the one container failure the diagnostics bundle used to hide behind
 * "无法解析容器": the server declares an audio track, but the demuxer in use exposed none.
 *
 * On Android this is nearly always an audio CodecID the platform Matroska extractor does not map
 * (TrueHD/MLP and friends), which is why the detail also names what the server declared. Only
 * codec identifiers are appended; track titles, languages and URLs stay out of [safeDetail].
 */
internal const val NATIVE_DIRECT_HIDDEN_AUDIO_DETAIL =
    "NativeDirect did not expose a server-declared audio track"

internal const val ENHANCED_HIDDEN_AUDIO_DETAIL =
    "Enhanced demux did not expose a server-declared audio track"

internal const val YCORE_HIDDEN_AUDIO_TRACK_MESSAGE =
    "YCore 2.0 无法解封装当前片源的音轨，其音频编码可能不受本机支持"

internal fun hiddenServerAudioTrackDetail(
    prefix: String,
    hints: YMediaSourceHints?,
): String {
    val declared = hints?.audioCodecs.orEmpty().map(String::lowercase).distinct()
    return if (declared.isEmpty()) {
        "$prefix (server audio tracks: ${hints?.audioTrackCount ?: 0})"
    } else {
        "$prefix (server audio codecs: ${declared.joinToString(",")})"
    }
}

internal fun YPlaybackException?.isHiddenServerAudioTrackFailure(): Boolean {
    if (this == null) return false
    if (category != YPlaybackFailureCategory.Container || stage != YPlaybackFailureStage.Demux) return false
    val detail = safeDetail ?: return false
    return detail.startsWith(NATIVE_DIRECT_HIDDEN_AUDIO_DETAIL) ||
        detail.startsWith(ENHANCED_HIDDEN_AUDIO_DETAIL)
}
