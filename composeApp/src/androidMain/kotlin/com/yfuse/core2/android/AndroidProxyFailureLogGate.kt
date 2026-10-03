package com.yfuse.core2.android

/**
 * Rate limit for the playback proxy's request-failure diagnostics.
 *
 * One broken stream fails every range request the player retries, often from several workers at
 * once, and the diagnostic store is bounded: unthrottled, a single outage would push everything
 * else out of it. The first failure of each kind is written at once; repeats within [intervalMs]
 * are only counted, and the count is reported with the next write of that kind.
 */
internal class AndroidProxyFailureLogGate(
    private val intervalMs: Long = 30_000L,
    private val maxKinds: Int = 16,
) {
    private class Window(
        val writtenAtMs: Long,
    ) {
        var suppressed = 0
    }

    private val lock = Any()
    private val windows = LinkedHashMap<String, Window>()

    /**
     * Null when this failure is only counted. Otherwise it should be written, together with the
     * returned number of same-kind failures suppressed since the previous write.
     */
    fun admit(
        kind: String,
        nowMs: Long,
    ): Int? =
        synchronized(lock) {
            val window = windows[kind]
            if (window != null && nowMs - window.writtenAtMs in 0L until intervalMs) {
                window.suppressed++
                return@synchronized null
            }
            windows.remove(kind)
            windows[kind] = Window(writtenAtMs = nowMs)
            // Forgetting the oldest kind only means its next failure is written straight away.
            while (windows.size > maxKinds) windows.remove(windows.keys.first())
            window?.suppressed ?: 0
        }
}
