package com.yfuse.feature.player

import android.util.Log
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.safeLogcat
import dev.jdtech.mpv.MPVLib

internal inline fun mpvCallSafely(
    instance: MPVLib?,
    tag: String,
    block: (MPVLib) -> Unit,
): Boolean {
    instance ?: return false
    return runCatching { block(instance) }.fold(
        onSuccess = { true },
        onFailure = {
            safeLogcat(Log.WARN, tag, "mpv call failed", it)
            AppLog.warning(
                category = "player.mpv",
                event = "engine_call_failed",
                message = "mpv engine call failed",
                throwable = it,
            )
            false
        },
    )
}
