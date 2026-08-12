package com.yfuse.feature.player

/** A terminal signal emitted by a native playback backend. */
internal data class NativePlaybackFailure(
    val message: String,
    val blocksAutomaticFallback: Boolean,
)

/**
 * Turns native-library text into the two failures that must be acted on immediately.
 *
 * Decoder/demuxer errors deliberately return null: those still benefit from the normal
 * direct -> transcode -> progressive chain. Authentication applies to every URL and engine,
 * while a render-context failure cannot be repaired by handing the same engine another URL.
 */
internal fun nativePlaybackLogFailure(details: String?): NativePlaybackFailure? {
    val text = details?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val normalized = text.lowercase()
    return when {
        normalized.isUnauthorizedFailure() -> NativePlaybackFailure(
            message = "服务器登录已失效（401），请重新登录该服务器",
            blocksAutomaticFallback = true,
        )
        normalized.isForbiddenFailure() -> NativePlaybackFailure(
            message = "当前账号没有播放权限，或服务器入口拒绝了访问（403）",
            blocksAutomaticFallback = true,
        )
        TERMINAL_RENDER_FAILURES.any(normalized::contains) -> NativePlaybackFailure(
            message = "播放器渲染器初始化失败，正在尝试其他播放器",
            blocksAutomaticFallback = false,
        )
        else -> null
    }
}

/** Classifies a failure which its caller already knows is terminal. */
internal fun terminalNativePlaybackFailure(
    fallbackMessage: String,
    details: String? = null,
): NativePlaybackFailure = nativePlaybackLogFailure(details) ?: NativePlaybackFailure(
    message = fallbackMessage,
    blocksAutomaticFallback = false,
)

private fun String.isUnauthorizedFailure(): Boolean =
    containsHttpStatus(401) ||
        contains("unauthorized") ||
        contains("not authorized") ||
        contains("authentication failed") ||
        contains("authentication required") ||
        contains("invalid token") ||
        contains("token expired") ||
        contains("expired token")

private fun String.isForbiddenFailure(): Boolean =
    containsHttpStatus(403) || contains("forbidden")

private fun String.containsHttpStatus(status: Int): Boolean {
    val value = status.toString()
    val index = indexOf(value)
    if (index < 0) return false
    val before = substring(maxOf(0, index - HTTP_STATUS_CONTEXT), index)
    val after = substring(index + value.length, minOf(length, index + value.length + HTTP_STATUS_CONTEXT))
    return before.contains("http") ||
        before.contains("status") ||
        before.contains("response") ||
        after.contains("unauthorized") ||
        after.contains("forbidden")
}

private const val HTTP_STATUS_CONTEXT = 48

private val TERMINAL_RENDER_FAILURES = listOf(
    "failed to initialize gpu",
    "failed initializing any suitable gpu context",
    "failed to create gpu context",
    "could not initialize video output",
    "failed to initialize video output",
    "video output initialization failed",
    "failed to attach surface",
    "failed to create android surface",
    "surface is invalid",
)
