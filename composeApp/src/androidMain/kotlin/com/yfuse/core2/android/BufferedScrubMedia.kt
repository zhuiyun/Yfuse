package com.yfuse.core2.android

import android.media.MediaDataSource
import com.yfuse.core2.adaptive.YHlsPlaylist
import com.yfuse.core2.adaptive.parseYHlsPlaylist
import com.yfuse.core2.network.YTransportCredentials
import java.io.Closeable
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One current player binding. Writers keep their binding, so late bytes from a previous item are dropped. */
internal object BufferedScrubMedia {
    private val active = AtomicReference<Session?>()

    fun attach(uri: String, headers: Map<String, String>, credentials: YTransportCredentials?): Session =
        Session(uri, headers, credentials).also { active.getAndSet(it)?.close() }

    fun lookup(uri: String, headers: Map<String, String>, credentials: YTransportCredentials?): Writer? =
        active.get()?.writer(uri, headers, credentials)

    fun hlsManifest(root: String?, manifest: String, base: String) {
        val session = active.get()?.takeIf { (it.root == root || it.root == base || it.child(base)) && it.plain } ?: return
        val parsed = runCatching { parseYHlsPlaylist(manifest, base) }.getOrNull() ?: return
        if (parsed is YHlsPlaylist.Master) { parsed.variants.forEach { session.addChild(it.uri) }; return }
        val media = parsed as? YHlsPlaylist.Media ?: return
        if (!media.isLive) media.segments.forEach {
            if (it.encryption == null && it.initialization == null && it.byteRange == null) {
                session.map(it.uri, it.startTimeUs, it.durationUs)
            }
        }
    }

    class Session internal constructor(
        val root: String,
        private val headers: Map<String, String>,
        private val credentials: YTransportCredentials?,
    ) : Closeable {
        internal val plain get() = headers.isEmpty() && credentials == null
        private val file = ScrubByteWindow()
        private data class Segment(val start: Long, val duration: Long)
        private val children = HashSet<String>()
        @Synchronized internal fun addChild(uri: String) { if (children.size < 64) children.add(uri) }
        @Synchronized internal fun child(uri: String) = uri in children
        private val segments = LinkedHashMap<String, Segment>()
        private val stores = LinkedHashMap<String, ScrubByteWindow>(8, .75f, true)
        private val closed = AtomicBoolean()

        @Synchronized internal fun map(uri: String, start: Long, duration: Long) {
            if (closed.get() || segments.size >= 8192 || start < 0 || duration <= 0) return
            segments[uri] = Segment(start, duration)
        }

        @Synchronized internal fun writer(uri: String, supplied: Map<String, String>, suppliedCredentials: YTransportCredentials?): Writer? {
            if (closed.get() || credentials != suppliedCredentials ||
                !headers.all { (k,v) -> supplied.entries.any { it.key.equals(k,true) && it.value == v } }
            ) return null
            if (uri == root) return Writer(this, file)
            if (!plain || uri !in segments) return null
            val store = stores.getOrPut(uri) { ScrubByteWindow(maxPages = 24) }
            if (stores.size > 4) stores.remove(stores.keys.first())?.reset()
            return Writer(this, store)
        }

        @Synchronized fun input(timeUs: Long): Pair<Input, Long>? {
            if (closed.get()) return null
            val entry = stores.keys.firstOrNull { key ->
                segments[key]?.let { timeUs >= it.start && timeUs - it.start < it.duration } == true
            }
            val window = if (entry == null) file else stores[entry] ?: return null
            if (window.size() <= 0) return null
            return Input(this, window) to (timeUs - (entry?.let { segments[it]?.start } ?: 0L))
        }

        fun current() = !closed.get() && active.get() === this

        @Synchronized override fun close() {
            if (!closed.compareAndSet(false, true)) return
            active.compareAndSet(this, null)
            file.reset()
            stores.values.forEach { it.reset() }
            stores.clear()
            segments.clear()
        }
    }

    class Writer internal constructor(private val session: Session, private val window: ScrubByteWindow) {
        fun put(position: Long, bytes: ByteArray, offset: Int, count: Int, total: Long) {
            if (session.current()) window.add(position, bytes, offset, count, total)
        }
        fun current() = session.current()
    }

    class Input internal constructor(private val session: Session, private val window: ScrubByteWindow) : MediaDataSource() {
        private val closed = AtomicBoolean()
        private val missed = AtomicBoolean()
        fun valid() = session.current() && !closed.get() && !missed.get()
        override fun getSize(): Long = if (valid()) window.size() else -1
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (!valid()) throw EOFException("Inactive playback buffer")
            return try { window.read(position, buffer, offset, size) } catch (e: EOFException) { missed.set(true); throw e }
        }
        override fun close() { closed.set(true) }
    }
}
