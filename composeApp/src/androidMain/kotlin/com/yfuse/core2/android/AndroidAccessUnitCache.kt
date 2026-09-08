package com.yfuse.core2.android

import java.util.IdentityHashMap

/** A sample keeps its preparation across codec backpressure; successful queueing releases it. */
internal class AndroidAccessUnitCache<S : Any, V> {
    private val values = IdentityHashMap<S, V>()

    fun getOrPrepare(
        sample: S,
        prepare: () -> V,
    ): V {
        if (values.containsKey(sample)) {
            @Suppress("UNCHECKED_CAST")
            return values[sample] as V
        }
        return prepare().also { values[sample] = it }
    }

    fun queued(sample: S) {
        values.remove(sample)
    }

    fun clear() {
        values.clear()
    }
}
