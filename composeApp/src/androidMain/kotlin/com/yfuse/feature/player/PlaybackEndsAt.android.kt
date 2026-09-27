package com.yfuse.feature.player

import android.text.format.DateFormat
import com.yfuse.core.util.androidAppContext
import java.util.TimeZone

/**
 * Read the way the top bar's clock reads it: the default zone, daylight saving included, and the
 * system's own 24-hour setting — so 结束于 is written in the same hand as the clock beside it.
 */
internal actual fun playerWallClock(): PlayerWallClock {
    val now = System.currentTimeMillis()
    val context = androidAppContext
    return PlayerWallClock(
        readAtEpochMs = now,
        utcOffsetMs = TimeZone.getDefault().getOffset(now).toLong(),
        use24Hour = context == null || DateFormat.is24HourFormat(context),
    )
}
