package com.yfuse.feature.player

/** Ownership of a gesture; position ticks do not change it, replacement media and outputs do. */
internal data class PlaybackInteractionContext(
    val engine: Any,
    val itemIndex: Int = 0,
    val itemId: String? = null,
    val serverId: String? = null,
    val versionId: String? = null,
    val streamUrl: String? = null,
    val engineGeneration: Int = 0,
    val runtimeSessionGeneration: Int = 0,
    val castRevision: Long = 0L,
    val castDeviceId: String? = null,
    val castQueueIndex: Int? = null,
    val casting: Boolean = false,
) {
    /** A cast stopping changes the output, but must still return to the same local video. */
    fun local(): PlaybackInteractionContext =
        copy(castRevision = 0L, castDeviceId = null, castQueueIndex = null, casting = false)
}

internal fun playbackSpeedUnavailableReason(
    watchLocked: Boolean,
    casting: Boolean,
): String? =
    when {
        watchLocked -> "房主控制播放速度"
        casting -> "当前投屏不支持调整倍速"
        else -> null
    }

/** Refused speed changes must not become a local choice or a remembered series preference. */
internal inline fun applyRememberedPlaybackSpeed(
    speed: Float,
    canChange: () -> Boolean,
    applySpeed: (Float) -> Boolean,
    rememberSpeed: (Float) -> Unit,
): Boolean {
    if (!canChange() || !applySpeed(speed)) return false
    rememberSpeed(speed)
    return true
}

/** Keep the bar present for the entire seek, including a finger held still between steps. */
internal fun pictureSeekKeepsControlsVisible(
    holdSeeking: Boolean,
    pictureScrubbing: Boolean,
    railScrubbing: Boolean,
): Boolean = holdSeeking || pictureScrubbing || railScrubbing
