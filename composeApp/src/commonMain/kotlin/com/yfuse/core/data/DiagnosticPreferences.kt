package com.yfuse.core.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val LOGCAT_OUTPUT_WINDOW_MS = 60L * 60L * 1_000L

/** Privacy-sensitive diagnostic switches. Every option defaults to disabled. */
class DiagnosticPreferences(
    private val settings: Settings,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _logcatEnabled = MutableStateFlow(
        persistedLogcatExpiry() > nowEpochMs(),
    )
    val logcatEnabled: StateFlow<Boolean> = _logcatEnabled.asStateFlow()

    fun setLogcatEnabled(enabled: Boolean) {
        synchronized(this) {
            if (enabled) {
                val now = nowEpochMs().coerceAtLeast(0L)
                val expiresAt = if (now > Long.MAX_VALUE - LOGCAT_OUTPUT_WINDOW_MS) {
                    Long.MAX_VALUE
                } else {
                    now + LOGCAT_OUTPUT_WINDOW_MS
                }
                settings.putLong(KEY_LOGCAT_EXPIRES_AT, expiresAt)
                _logcatEnabled.value = true
            } else {
                settings.remove(KEY_LOGCAT_EXPIRES_AT)
                _logcatEnabled.value = false
            }
        }
    }

    /** Rechecks the one-hour privacy window; called on every attempted Logcat write. */
    fun isLogcatEnabledNow(): Boolean = logcatOutputRemainingMs() > 0L

    /** Lets the settings UI turn itself off at the exact persisted deadline. */
    fun logcatOutputRemainingMs(): Long = synchronized(this) {
        val expiresAt = persistedLogcatExpiry()
        val now = nowEpochMs()
        val remaining = if (expiresAt > now) expiresAt - now else 0L
        if (remaining == 0L && _logcatEnabled.value) {
            settings.remove(KEY_LOGCAT_EXPIRES_AT)
            _logcatEnabled.value = false
        }
        remaining
    }

    private companion object {
        const val KEY_LOGCAT_EXPIRES_AT = "diagnostics.logcat.expiresAt"
    }

    private fun persistedLogcatExpiry(): Long =
        settings.getLong(KEY_LOGCAT_EXPIRES_AT, 0L).coerceAtLeast(0L)
}
