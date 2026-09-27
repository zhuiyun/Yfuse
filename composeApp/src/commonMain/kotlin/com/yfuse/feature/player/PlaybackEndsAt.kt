package com.yfuse.feature.player

import kotlin.math.roundToLong

/**
 * The device's wall clock, as far as 结束于 needs it: where local time stands against UTC and
 * whether the user reads the clock in 24 hours.
 */
internal data class PlayerWallClock(
    /** When this was read. Also what makes each minute's reading a new value to observe. */
    val readAtEpochMs: Long,
    /** Local time minus UTC, daylight saving included. */
    val utcOffsetMs: Long,
    val use24Hour: Boolean,
)

/**
 * 结束于 — the local time the file ends if it plays on from [positionMs] at [speed]: the time
 * left, divided by the speed, after [nowEpochMs]. Plex and Kodi show the same readout; 1.5× is
 * the reason it cannot simply be the duration added to the clock.
 *
 * Null where there is nothing honest to say: no duration yet (or a live stream), nothing left
 * to play, or a speed that does not move.
 */
internal fun playbackEndsAtLabel(
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    nowEpochMs: Long,
    clock: PlayerWallClock,
): String? {
    if (durationMs <= 0L || !speed.isFinite() || speed <= 0f) return null
    val remainingMs = durationMs - positionMs.coerceIn(0L, durationMs)
    if (remainingMs <= 0L) return null
    val endsAtEpochMs = nowEpochMs + (remainingMs / speed.toDouble()).roundToLong()
    return "结束于 ${wallClockLabel(endsAtEpochMs + clock.utcOffsetMs, clock.use24Hour)}"
}

/**
 * A local time, given as milliseconds since the local epoch, the way the player's own clock
 * writes it: "21:47", or "下午9:47" where the device keeps a 12-hour clock. The seconds are
 * dropped rather than rounded, as a clock's are.
 */
internal fun wallClockLabel(
    localEpochMs: Long,
    use24Hour: Boolean,
): String {
    val minuteOfDay = localEpochMs.floorDiv(MINUTE_MS).mod(MINUTES_PER_DAY)
    val hour = (minuteOfDay / 60L).toInt()
    val minute = (minuteOfDay % 60L).toString().padStart(2, '0')
    if (use24Hour) return "${hour.toString().padStart(2, '0')}:$minute"
    val half = if (hour < 12) "上午" else "下午"
    val twelve = (hour % 12).let { if (it == 0) 12 else it }
    return "$half$twelve:$minute"
}

private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_DAY = 1_440L
