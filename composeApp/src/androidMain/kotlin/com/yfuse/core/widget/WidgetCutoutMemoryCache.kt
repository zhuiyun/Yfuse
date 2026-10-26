package com.yfuse.core.widget

/** Small metadata cache only; files and decoded images retain their existing disk/Coil budgets. */
internal class WidgetCutoutMemoryCache(
    private val capacity: Int = 64,
) {
    private val entries = LinkedHashMap<String, WidgetCutoutArtwork>()
    private var generation = 0L

    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun peek(source: String): WidgetCutoutArtwork? = entries.remove(source)?.also { entries[source] = it }

    @Synchronized
    fun put(
        source: String,
        artwork: WidgetCutoutArtwork?,
        expectedGeneration: Long,
    ) {
        if (expectedGeneration != generation) return
        entries.remove(source)
        if (artwork != null) entries[source] = artwork
        if (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun clear() {
        generation++
        entries.clear()
    }
}
