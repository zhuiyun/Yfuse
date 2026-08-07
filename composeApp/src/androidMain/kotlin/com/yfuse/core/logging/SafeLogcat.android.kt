package com.yfuse.core.logging

import android.util.Log
import com.yfuse.BuildConfig

/** Writes a redacted message and stack trace without passing the raw Throwable to Android. */
internal fun safeLogcat(
    priority: Int,
    tag: String,
    message: String,
    throwable: Throwable? = null,
    event: String? = null,
    attributes: Map<String, String> = emptyMap(),
) {
    // Exportable diagnostics remain available in release builds, but Logcat is a globally
    // observable device surface. Keep it development-only even though the payload is redacted.
    if (!BuildConfig.DEBUG) return
    val safeTag = redactDiagnosticText(tag).take(23).ifBlank { "Yfuse" }
    val safePayload = formatSafeLogcatMessage(
        event = event,
        message = message,
        attributes = attributes,
        throwableText = throwable?.let(Log::getStackTraceString),
    )
    Log.println(priority, safeTag, safePayload)
}
