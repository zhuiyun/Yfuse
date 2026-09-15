package com.yfuse.feature.player

import java.io.Closeable
import java.util.UUID

internal data class PlaybackProxyRoute(
    val upstreamUrl: String,
    val cacheable: Boolean,
)

/**
 * Retains the current HLS manifest graph, in-flight reads and a bounded grace window for old URLs.
 * VOD segments remain reachable for seeking; sliding live windows retire their obsolete segments.
 */
internal class PlaybackProxyRoutes(
    private val retiredLimit: Int = 4_096,
    private val graceMs: Long = 120_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : Closeable {
    private class Entry(
        val route: PlaybackProxyRoute,
        var readers: Int = 0,
        var children: Set<String> = emptySet(),
        var retiredAtMs: Long? = null,
    )

    private val entries = linkedMapOf<String, Entry>()
    private val ids = mutableMapOf<PlaybackProxyRoute, String>()
    private var rootId: String? = null
    private var closed = false
    private var nextPruneAtMs = Long.MIN_VALUE

    init {
        require(retiredLimit >= 0 && graceMs >= 0L)
    }

    @Synchronized
    fun registerRoot(route: PlaybackProxyRoute): String? {
        if (closed) return null
        return register(route).also {
            rootId = it
            prune(force = true)
        }
    }

    @Synchronized
    fun acquire(id: String): Lease? {
        if (closed) return null
        prune()
        val entry = entries[id] ?: return null
        entry.readers++
        return Lease(id, entry.route) { release(id) }
    }

    @Synchronized
    fun rewriteManifest(
        parent: Lease,
        manifest: String,
        upstreamUrl: String,
        localUrl: (String) -> String,
    ): String {
        val children = linkedSetOf<String>()
        val rewritten =
            rewriteMpvHlsManifest(manifest, upstreamUrl) { url ->
                if (closed || !shouldProxyMpvNetworkUrl(url)) {
                    url
                } else {
                    val id = register(PlaybackProxyRoute(url, cacheable = false))
                    children += id
                    localUrl(id)
                }
            }
        entries[parent.id]?.children = children
        prune(force = true)
        return rewritten
    }

    @Synchronized
    override fun close() {
        closed = true
        rootId = null
        entries.clear()
        ids.clear()
    }

    @Synchronized
    internal fun entryCount(): Int {
        prune(force = true)
        return entries.size
    }

    private fun register(route: PlaybackProxyRoute): String =
        ids.getOrPut(route) {
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .also { entries[it] = Entry(route) }
        }

    @Synchronized
    private fun release(id: String) {
        entries[id]?.let { it.readers-- }
        prune()
    }

    /** Manifest updates prune once in a batch; segment traffic scans at most once a second. */
    private fun prune(force: Boolean = false) {
        val now = nowMs()
        if (!force && now < nextPruneAtMs) return
        nextPruneAtMs = now + 1_000L
        val reachable = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        rootId?.let(pending::addLast)
        entries.forEach { (id, entry) -> if (entry.readers > 0) pending.addLast(id) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (reachable.add(id)) entries[id]?.children?.forEach(pending::addLast)
        }
        val retired = mutableListOf<Pair<String, Long>>()
        entries.forEach { (id, entry) ->
            if (id in reachable) {
                entry.retiredAtMs = null
            } else {
                val since = entry.retiredAtMs ?: now.also { entry.retiredAtMs = it }
                retired += id to since
            }
        }
        val ordered = retired.sortedBy { it.second }
        ordered.forEachIndexed { index, (id, since) ->
            if (now - since >= graceMs || index < ordered.size - retiredLimit) {
                entries.remove(id)?.let { ids.remove(it.route) }
            }
        }
    }

    internal class Lease(
        val id: String,
        val route: PlaybackProxyRoute,
        private val onClose: () -> Unit,
    ) : Closeable {
        private var closed = false

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            onClose()
        }
    }
}
