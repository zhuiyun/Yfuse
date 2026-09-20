package com.yfuse.core2.android

import com.yfuse.core.logging.AppLog
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YTransportCredentials
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** In-memory only: authorization and signed URLs must never become diagnostic attributes. */
internal data class AndroidProxyMediaIdentity(
    val uri: String,
    val headers: Map<String, String>,
    val credentials: YTransportCredentials?,
    val cacheIdentity: YCacheIdentity?,
    val cacheMaximumBytes: Long,
) {
    override fun toString(): String = "AndroidProxyMediaIdentity(redacted)"
}

/**
 * A bounded pool local to one playback proxy. Leases share validated metadata and the immutable
 * startup slice, not a reader: transport cancellation, prefetch and close remain request-owned.
 */
internal class AndroidProxyMediaSessions(
    private val maximumEntries: Int = 16,
) : Closeable {
    private val entries = LinkedHashMap<AndroidProxyMediaIdentity, Entry>(16, 0.75f, true)
    private var closed = false

    private class Entry(
        val session: AndroidMediaRepresentationSession = AndroidMediaRepresentationSession(),
        var readers: Int = 0,
    )

    init {
        require(maximumEntries > 0)
    }

    @Synchronized
    fun acquire(identity: AndroidProxyMediaIdentity): Lease {
        check(!closed) { "Playback proxy is closed" }
        val existing = entries[identity]
        val entry = existing ?: Entry()
        if (existing == null) {
            if (entries.size >= maximumEntries) {
                val idle = entries.entries.firstOrNull { it.value.readers == 0 }
                if (idle != null) {
                    entries.remove(idle.key)
                    idle.value.session.close()
                }
            }
            // A burst of simultaneous distinct media must not grow the retained pool indefinitely.
            if (entries.size < maximumEntries) entries[identity] = entry
        }
        entry.readers++
        return Lease(entry.session) {
            synchronized(this) {
                entry.readers--
                if (entries[identity] !== entry && entry.readers == 0) entry.session.close()
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        entries.values.forEach { it.session.close() }
        entries.clear()
    }

    class Lease internal constructor(
        val session: AndroidMediaRepresentationSession,
        private val release: () -> Unit,
    ) : Closeable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}

internal class AndroidMediaRepresentationSession(
    private val clock: () -> Long = System::nanoTime,
    private val validationTtlNs: Long = TimeUnit.SECONDS.toNanos(30L),
) : Closeable {
    class InitialRange(
        val bytes: ByteArray,
        val contentLength: Long?,
        val entityTag: String?,
    )

    class Snapshot internal constructor(
        val initial: InitialRange,
        internal val revision: Long,
    )

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val logId = UUID.randomUUID().toString().take(8)
    private val loggedEvents = mutableSetOf<String>()
    private var revision = 0L
    private var loading: Any? = null
    private var reusable: Snapshot? = null
    private var validatedAtNs = 0L
    private var closed = false

    fun obtain(
        checkActive: () -> Unit,
        load: () -> InitialRange,
    ): Snapshot {
        while (true) {
            checkActive()
            val token = Any()
            var owner = false
            var expectedRevision = 0L
            val cached =
                lock.withLock {
                    checkOpen()
                    reusable?.takeIf { clock() - validatedAtNs < validationTtlNs }?.also { return@withLock it }
                        ?: run {
                            if (loading == null) {
                                loading = token
                                expectedRevision = revision
                                owner = true
                            } else {
                                // A cancelled waiter never cancels the owner's transport.
                                changed.await(50L, TimeUnit.MILLISECONDS)
                            }
                            null
                        }
                }
            if (cached != null) {
                checkActive()
                logOnce("proxy_media_validation_reused", "Reused validated media within the playback proxy")
                return cached
            }
            if (!owner) continue
            try {
                val fresh = load()
                checkActive()
                var replaced = false
                val snapshot =
                    lock.withLock {
                        checkOpen()
                        if (revision != expectedRevision) throw CancellationException("Media representation changed")
                        reusable?.initial?.let { previous ->
                            if (previous.entityTag != fresh.entityTag ||
                                previous.contentLength != fresh.contentLength
                            ) {
                                revision++
                                replaced = true
                            }
                        }
                        reusable = null
                        validatedAtNs = clock()
                        Snapshot(fresh, revision).also {
                            // Without a strong validator, a new request must validate the origin again.
                            // In particular it may never treat old persisted bytes as current media.
                            if (fresh.contentLength != null && fresh.entityTag.isStrongEntityTag()) reusable = it
                        }
                    }
                if (replaced) {
                    logOnce("proxy_media_invalidated", "Origin representation changed; proxy readers must reopen")
                }
                logOnce(
                    "proxy_media_validated",
                    "Validated the media representation for the playback proxy",
                    mapOf(
                        "reusable" to (fresh.contentLength != null && fresh.entityTag.isStrongEntityTag()).toString(),
                    ),
                )
                return snapshot
            } finally {
                lock.withLock {
                    if (loading === token) loading = null
                    changed.signalAll()
                }
            }
        }
    }

    /** Keep disk acceptance atomic with session invalidation; no network work runs under this lock. */
    fun accept(
        snapshot: Snapshot,
        acceptCache: () -> Unit,
    ): Boolean =
        lock.withLock {
            if (closed || snapshot.revision != revision) return@withLock false
            acceptCache()
            true
        }

    fun isCurrent(snapshot: Snapshot): Boolean = lock.withLock { !closed && snapshot.revision == revision }

    fun invalidate(
        snapshot: Snapshot?,
        invalidateCache: () -> Unit,
    ) {
        val invalidated =
            lock.withLock {
                if (closed || snapshot != null && snapshot.revision != revision) return@withLock false
                revision++
                reusable = null
                // A late old reader must never clear bytes already accepted by a newer revision.
                // This shares the same lock as accept(), including the disk mutation itself.
                try {
                    invalidateCache()
                } finally {
                    changed.signalAll()
                }
                true
            }
        if (invalidated) {
            logOnce("proxy_media_invalidated", "Origin representation changed; proxy readers must reopen")
        }
    }

    override fun close() {
        lock.withLock {
            closed = true
            reusable = null
            changed.signalAll()
        }
    }

    private fun checkOpen() {
        if (closed) throw CancellationException("Playback media session is closed")
    }

    private fun logOnce(
        event: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ) {
        if (!lock.withLock { loggedEvents.add(event) }) return
        AppLog.info("player.core2", event, message, attributes = attributes + ("proxySession" to logId))
    }
}

private fun String?.isStrongEntityTag(): Boolean = this != null && length >= 2 && startsWith('"') && endsWith('"')
