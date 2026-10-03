package com.yfuse.core.data

/**
 * 手势 — how the player's picture answers a finger, as 播放设置 sets it.
 *
 * Defaults are what the player did before each setting existed, so nobody's hands change underneath
 * them: ten-second double taps, 临时倍速 on a held middle, brightness on the left, a double tap in
 * the middle to pause. One default changed deliberately, for the habit most viewers bring from every
 * other Chinese player: the lock opens with a tap rather than a long press ([unlockByLongPress]).
 * The two sides of the picture hold nothing; only the middle does.
 */
data class PlayerGestureSettings(
    /** 双击步长: how far a double tap on either side moves, and J / L on a keyboard with it. */
    val doubleTapSeekSeconds: Int = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
    /** 中间长按: whether holding the middle third plays faster for as long as it is held. */
    val centerHoldSpeedBoost: Boolean = true,
    /** 亮度与音量左右互换: volume down the left side of the picture and brightness down the right. */
    val swapBrightnessVolume: Boolean = false,
    /** 双击 · 全屏暂停: a double tap anywhere plays or pauses, and never seeks. */
    val doubleTapPausesAnywhere: Boolean = false,
    /** 解锁方式 · 长按: the lock key opens only under a held press, for a child or a pocket. */
    val unlockByLongPress: Boolean = false,
) {
    /** [doubleTapSeekSeconds] as the seek it makes. */
    val doubleTapSeekMs: Long
        get() = normalizedDoubleTapSeekSeconds(doubleTapSeekSeconds) * 1_000L

    companion object {
        const val DEFAULT_DOUBLE_TAP_SEEK_SECONDS = 10

        /** The steps 播放设置 offers, in the order it lists them. */
        val DOUBLE_TAP_SEEK_CHOICES: List<Int> = listOf(5, 10, 15, 30)
    }
}

/**
 * A stored step the panel does not offer — a hand-edited file, or a choice a later version
 * dropped — falls back to the default rather than to whatever number happens to be there.
 */
internal fun normalizedDoubleTapSeekSeconds(stored: Int): Int =
    stored.takeIf { it in PlayerGestureSettings.DOUBLE_TAP_SEEK_CHOICES }
        ?: PlayerGestureSettings.DEFAULT_DOUBLE_TAP_SEEK_SECONDS
