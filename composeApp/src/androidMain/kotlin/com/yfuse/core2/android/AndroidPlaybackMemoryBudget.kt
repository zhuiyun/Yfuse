package com.yfuse.core2.android

import android.app.ActivityManager
import android.content.Context
import java.io.Closeable

internal enum class PlaybackBufferKind(
    val weight: Int,
) {
    Transport(4),
    Demux(3),
    Render(4),
    Subtitle(1),
    CacheWrite(1),
    Preload(1),
}

/** Budgets app-owned playback buffers. Codec/driver and the rest of the app remain outside it. */
internal class PlaybackMemoryPool(
    private val normalBytes: Long,
) {
    private val leases = mutableListOf<PlaybackMemoryLease>()
    private var pressure = false

    @Synchronized
    fun acquire(
        kind: PlaybackBufferKind,
        requestedBytes: Long,
    ): PlaybackMemoryLease {
        require(requestedBytes >= 0L)
        return PlaybackMemoryLease(this, kind, requestedBytes).also {
            leases.add(it)
            rebalance()
        }
    }

    @Synchronized
    fun setPressure(value: Boolean) {
        if (pressure == value) return
        pressure = value
        rebalance()
    }

    @Synchronized
    internal fun release(lease: PlaybackMemoryLease) {
        leases.remove(lease)
        lease.limitBytes = 0L
        rebalance()
    }

    private fun rebalance() {
        var remaining = if (pressure) normalBytes / 2 else normalBytes
        val pending = leases.toMutableList()
        if (pressure) {
            pending.removeAll {
                (it.kind == PlaybackBufferKind.Preload || it.kind == PlaybackBufferKind.CacheWrite).also { drop ->
                    if (drop) it.limitBytes = 0L
                }
            }
        }
        while (pending.isNotEmpty()) {
            val weights = pending.sumOf { it.kind.weight }.toLong()
            val satisfied = pending.filter { it.requestedBytes <= remaining * it.kind.weight / weights }
            if (satisfied.isEmpty()) {
                pending.forEach { it.limitBytes = remaining * it.kind.weight / weights }
                break
            }
            satisfied.forEach {
                it.limitBytes = it.requestedBytes
                remaining -= it.requestedBytes
            }
            pending.removeAll(satisfied.toSet())
        }
    }
}

internal class PlaybackMemoryLease internal constructor(
    private val pool: PlaybackMemoryPool,
    internal val kind: PlaybackBufferKind,
    internal val requestedBytes: Long,
) : Closeable {
    @Volatile var limitBytes: Long = 0L
        internal set

    override fun close() = pool.release(this)
}

internal fun playbackMemoryBudgetBytes(
    heapBytes: Long,
    lowRam: Boolean,
): Long = (heapBytes / if (lowRam) 5 else 3).coerceIn(12L * MIB, if (lowRam) 32L * MIB else 96L * MIB)

/** OS pressure can concern the whole device while this process still has free Java heap. */
internal class PlaybackMemoryPressurePolicy(
    private val nowNs: () -> Long = System::nanoTime,
    private val trimCooldownNs: Long = 30_000_000_000L,
) {
    init {
        require(trimCooldownNs >= 0L)
    }

    private var lastTrimNs: Long? = null

    @Synchronized
    fun trim() {
        lastTrimNs = nowNs()
    }

    @Synchronized
    fun allowsSpeculation(
        background: Boolean,
        freeHeapBytes: Long,
        maximumHeapBytes: Long,
    ): Boolean {
        require(freeHeapBytes >= 0L && maximumHeapBytes > 0L)
        val coolingDown = lastTrimNs?.let { nowNs() - it < trimCooldownNs } == true
        return !background && !coolingDown && freeHeapBytes >= maximumHeapBytes / 6L
    }
}

internal object AndroidPlaybackMemoryBudget {
    @Volatile private var pool = PlaybackMemoryPool(playbackMemoryBudgetBytes(Runtime.getRuntime().maxMemory(), false))
    private val pressurePolicy = PlaybackMemoryPressurePolicy()

    @Volatile private var background = false

    @Volatile var allowsSpeculativeWork: Boolean = true
        private set

    fun initialize(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val heap = minOf(Runtime.getRuntime().maxMemory(), (manager?.memoryClass ?: 256).toLong() * MIB)
        pool = PlaybackMemoryPool(playbackMemoryBudgetBytes(heap, manager?.isLowRamDevice == true))
    }

    fun acquire(
        kind: PlaybackBufferKind,
        requestedBytes: Long,
    ): PlaybackMemoryLease {
        refreshPressure()
        return pool.acquire(kind, requestedBytes)
    }

    /** Also sampled by active I/O; modern Android does not deliver all legacy trim levels. */
    @Synchronized
    fun refreshPressure() {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        allowsSpeculativeWork =
            pressurePolicy.allowsSpeculation(background, runtime.maxMemory() - used, runtime.maxMemory())
        pool.setPressure(!allowsSpeculativeWork)
    }

    @Synchronized
    fun setBackground(value: Boolean) {
        background = value
        refreshPressure()
    }

    @Synchronized
    fun trim() {
        pressurePolicy.trim()
        allowsSpeculativeWork = false
        pool.setPressure(true)
    }
}

private const val MIB = 1024L * 1024L
