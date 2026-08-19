package com.yfuse.core2.quirk

import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec

/**
 * Privacy-safe identity for one Core2 hardware/media route.
 *
 * Never include provider ids, titles, URLs or auth headers here. The key intentionally contains only
 * capability facts that can explain a deterministic backend failure on this device.
 */
data class YCore2FailureKey(
    val route: YPlaybackRoute,
    val container: YContainer,
    val videoCodec: YVideoCodec,
    val hdrType: YHdrType,
    val dolbyVisionProfile: Int? = null,
    val decoderName: String? = null,
)

data class YCore2FailureRecord(
    val key: YCore2FailureKey,
    val category: YPlaybackFailureCategory,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val failureCount: Int,
    val blockedUntilEpochMs: Long,
) {
    init {
        require(failureCount > 0)
        require(lastSeenEpochMs >= firstSeenEpochMs)
        require(blockedUntilEpochMs >= lastSeenEpochMs)
    }
}

/** Persistence seam; Settings/SQL implementations can be attached without changing route policy. */
interface YCore2FailureStore {
    fun load(): List<YCore2FailureRecord>

    fun replace(records: List<YCore2FailureRecord>)
}

class InMemoryYCore2FailureStore : YCore2FailureStore {
    private var records: List<YCore2FailureRecord> = emptyList()

    override fun load(): List<YCore2FailureRecord> = records

    override fun replace(records: List<YCore2FailureRecord>) {
        this.records = records.toList()
    }
}

/**
 * Core2-specific route failure memory.
 *
 * This does not share the Legacy `PlayerEngine` persistence schema. A failed Core2 experiment can
 * therefore cool down only that precise Core2 route without blacklisting Exo/mpv/MDK. Transient
 * auth/network/DRM failures and unclassified failures are never learned as hardware quirks.
 */
class YCore2FailureLedger(
    private val store: YCore2FailureStore,
    private val nowEpochMs: () -> Long,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
) {
    init {
        require(cooldownMs > 0L)
        require(maxRecords > 0)
    }

    @Synchronized
    fun recordFailure(
        key: YCore2FailureKey,
        category: YPlaybackFailureCategory,
    ): YCore2FailureRecord? {
        if (!category.penalizesCore2Route()) return null
        val now = nowEpochMs().coerceAtLeast(0L)
        val existing = store.load().firstOrNull { it.key == key && it.category == category }
        val updated =
            if (existing == null) {
                YCore2FailureRecord(
                    key = key,
                    category = category,
                    firstSeenEpochMs = now,
                    lastSeenEpochMs = now,
                    failureCount = 1,
                    blockedUntilEpochMs = saturatingAdd(now, cooldownMs),
                )
            } else {
                existing.copy(
                    lastSeenEpochMs = now,
                    failureCount = (existing.failureCount + 1).coerceAtMost(Int.MAX_VALUE),
                    blockedUntilEpochMs = saturatingAdd(now, cooldownMs),
                )
            }
        val retained =
            store
                .load()
                .filterNot { it.key == key && it.category == category }
                .plus(updated)
                .filter { it.blockedUntilEpochMs > now }
                .sortedByDescending(YCore2FailureRecord::lastSeenEpochMs)
                .take(maxRecords)
        store.replace(retained)
        return updated
    }

    @Synchronized
    fun isBlocked(key: YCore2FailureKey): Boolean {
        val now = nowEpochMs().coerceAtLeast(0L)
        val records = prune(now)
        return records.any { it.key == key && it.blockedUntilEpochMs > now }
    }

    @Synchronized
    fun activeFailures(key: YCore2FailureKey): List<YCore2FailureRecord> {
        val now = nowEpochMs().coerceAtLeast(0L)
        return prune(now).filter { it.key == key && it.blockedUntilEpochMs > now }
    }

    @Synchronized
    fun clear(key: YCore2FailureKey) {
        store.replace(store.load().filterNot { it.key == key })
    }

    @Synchronized
    fun clearAll() {
        store.replace(emptyList())
    }

    private fun prune(now: Long): List<YCore2FailureRecord> {
        val active =
            store
                .load()
                .filter { it.blockedUntilEpochMs > now }
                .sortedByDescending(YCore2FailureRecord::lastSeenEpochMs)
                .take(maxRecords)
        store.replace(active)
        return active
    }
}

fun YPlaybackFailureCategory.penalizesCore2Route(): Boolean =
    when (this) {
        YPlaybackFailureCategory.Container,
        YPlaybackFailureCategory.Decoder,
        YPlaybackFailureCategory.Renderer,
        YPlaybackFailureCategory.AudioSink,
        -> true
        YPlaybackFailureCategory.Authorization,
        YPlaybackFailureCategory.Drm,
        YPlaybackFailureCategory.Network,
        YPlaybackFailureCategory.Unknown,
        -> false
    }

private fun saturatingAdd(
    value: Long,
    increment: Long,
): Long = if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

private const val DEFAULT_COOLDOWN_MS = 7L * 24L * 60L * 60L * 1_000L
private const val DEFAULT_MAX_RECORDS = 128
