package com.yfuse.core.performance

import kotlin.concurrent.Volatile

/**
 * What the UI is doing right now, so slow frames can be attributed to it.
 *
 * A gesture or transition calls [put] as it begins (`put("gesture", "lift_menu")`) and [remove]
 * with the same key once it settles. Every started frame monitor forwards the pair to its
 * window's JankStats, which files each frame under the states active during it, and the
 * diagnostic export reports frame overrun per state. With no monitor started (host tests,
 * previews, a window that never attached one) both calls return at once.
 *
 * Keys and values are a small fixed vocabulary in lower snake_case, never an id or a title:
 * each distinct pair becomes one row of the export and the number of rows is capped.
 */
object JankStatsState {
    /** One started window's JankStats, as shared code sees it. */
    internal interface Sink {
        fun put(
            key: String,
            value: String,
        )

        fun remove(key: String)
    }

    private val lock = Any()

    // Replaced, never mutated: gesture callbacks read it without taking the lock.
    @Volatile
    private var sinks: List<Sink> = emptyList()

    fun put(
        key: String,
        value: String,
    ) {
        for (sink in sinks) sink.put(key, value)
    }

    fun remove(key: String) {
        for (sink in sinks) sink.remove(key)
    }

    internal fun attach(sink: Sink) {
        synchronized(lock) { if (sink !in sinks) sinks = sinks + sink }
    }

    internal fun detach(sink: Sink) {
        synchronized(lock) { sinks = sinks - sink }
    }
}
