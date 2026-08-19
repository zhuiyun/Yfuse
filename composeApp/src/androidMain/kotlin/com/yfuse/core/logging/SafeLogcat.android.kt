package com.yfuse.core.logging

import android.util.Log
import com.yfuse.core.data.DiagnosticPreferences

/** Process-wide gate backed by the same persisted preference shown in diagnostics settings. */
internal object SafeLogcatOutputGate {
    @Volatile
    private var preferences: DiagnosticPreferences? = null

    fun initialize(preferences: DiagnosticPreferences) {
        this.preferences = preferences
    }

    fun isEnabled(): Boolean = preferences?.isLogcatEnabledNow() == true
}

/** Writes a redacted message and stack trace without passing the raw Throwable to Android. */
internal fun safeLogcat(
    priority: Int,
    tag: String,
    message: String,
    throwable: Throwable? = null,
    event: String? = null,
    attributes: Map<String, String> = emptyMap(),
) {
    // Logcat is a globally observable device surface. It stays off until the user explicitly
    // enables live output in diagnostics; the persisted export log remains available either way.
    if (!SafeLogcatOutputGate.isEnabled()) return
    val safeTag = redactDiagnosticText(tag).take(23).ifBlank { "Yfuse" }
    val safePayload =
        formatSafeLogcatMessage(
            event = event,
            message = message,
            attributes = attributes,
            throwableText = throwable?.let(Log::getStackTraceString),
        )
    Log.println(priority, safeTag, safePayload)
}
