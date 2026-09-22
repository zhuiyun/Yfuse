package com.yfuse.core.data

import com.yfuse.core.logging.AppLog

/** A read failed without proving that the stored credentials are missing or corrupt. */
class ServerSessionRestoreException(
    cause: Throwable,
) : IllegalStateException("暂时无法读取已保存的登录信息，请解锁设备后重试", cause)

/** Includes dependency-injection wrappers without treating unrelated startup errors as recoverable. */
fun Throwable.isServerSessionRestoreFailure(): Boolean {
    var current: Throwable? = this
    repeat(16) {
        if (current is ServerSessionRestoreException) return true
        current = current?.cause ?: return false
    }
    return false
}

/** Publish no registry and start no synchronizers until the complete saved snapshot is available. */
class ServerSessionStartup(
    private val restore: () -> Unit,
    private val startServices: () -> Unit,
) {
    var ready: Boolean = false
        private set

    fun start(): Boolean {
        if (ready) return true
        try {
            restore()
        } catch (error: Exception) {
            if (!error.isServerSessionRestoreFailure()) throw error
            AppLog.warning(
                category = "server.registry",
                event = "session_restore_deferred",
                message = "Saved sessions remain untouched while secure storage is unavailable",
                throwable = error,
            )
            return false
        }
        startServices()
        ready = true
        return true
    }
}
