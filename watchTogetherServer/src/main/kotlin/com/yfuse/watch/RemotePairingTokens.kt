package com.yfuse.watch

import com.yfuse.watch.protocol.WatchProtocol
import java.security.MessageDigest

/** What a phone's `remoteJoin` proved about the id it claimed. */
internal enum class PairingCheck {
    /** No token on record for this phone and television: the claim stands as it always did. */
    Unknown,

    /** The phone presented the token its television's admission was bound to. */
    Verified,

    /** A token is on record and the phone did not present it: someone else may be using the id. */
    Mismatch,
}

/**
 * 手机遥控 pairing tokens, as digests. A television remembers a phone by the id the phone gives,
 * so any phone of the same account could give the id of one the television trusts and be let in
 * without a question. When a television admits a phone that can hold a token, the relay hands it
 * one ([issue]) bound to that television's session and the phone's id; from then on a phone
 * claiming that id without the token is shown to the television as an unknown phone ([check]).
 *
 * Bounded by [maxEntries] (least recently used go first) and by [ttlMs] since last use, and kept
 * in [store] when there is one, so a restart of the relay does not reset every pairing.
 */
internal class RemotePairingTokens(
    private val store: WatchStateStore? = null,
    private val maxEntries: Int = MAX_PAIRING_RECORDS,
    private val ttlMs: Long = PAIRING_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val records = LinkedHashMap<String, PersistedPairing>(16, 0.75f, true)

    init {
        require(maxEntries > 0 && ttlMs > 0L)
        store
            ?.let { runCatching { it.loadPairings(usedSinceMs = now() - ttlMs) }.getOrDefault(emptyList()) }
            ?.forEach { records[it.key] = it }
        trimLocked()
    }

    fun check(
        key: String,
        presented: String?,
    ): PairingCheck {
        val touched =
            synchronized(this) {
                val nowMs = now()
                val record = records[key] ?: return PairingCheck.Unknown
                if (nowMs - record.lastUsedAtMs >= ttlMs) {
                    records.remove(key)
                    return PairingCheck.Unknown
                }
                if (!WatchProtocol.isValidPairingToken(presented)) return PairingCheck.Mismatch
                if (!MessageDigest.isEqual(record.tokenDigest, digest(key, presented!!))) return PairingCheck.Mismatch
                // Use keeps a pairing alive; the store hears of it about once a day per phone.
                if (nowMs - record.lastUsedAtMs < TOUCH_PERSIST_INTERVAL_MS) return PairingCheck.Verified
                record.copy(lastUsedAtMs = nowMs).also { records[key] = it }
            }
        store?.let { runCatching { it.savePairing(touched) } }
        return PairingCheck.Verified
    }

    /** A fresh token for [key], replacing any earlier one; only its digest is kept. */
    fun issue(key: String): String {
        val token = newCapability()
        val record = PersistedPairing(key, digest(key, token), now())
        synchronized(this) {
            records[key] = record
            trimLocked()
        }
        store?.let { runCatching { it.savePairing(record) } }
        return token
    }

    @Synchronized
    internal fun size(): Int = records.size

    private fun trimLocked() {
        val eldest = records.entries.iterator()
        while (records.size > maxEntries && eldest.hasNext()) {
            eldest.next()
            eldest.remove()
        }
    }

    private fun digest(
        key: String,
        token: String,
    ): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest("remote-pairing-v1\u0000$key\u0000$token".toByteArray(Charsets.UTF_8))

    private companion object {
        const val MAX_PAIRING_RECORDS = 50_000
        const val PAIRING_TTL_MS = 180L * 24 * 60 * 60 * 1_000
        const val TOUCH_PERSIST_INTERVAL_MS = 24L * 60 * 60 * 1_000
    }
}

/** One phone on one television of one account. */
internal fun remotePairingKey(
    userId: String,
    televisionSessionId: String,
    deviceId: String,
): String = "$userId\u0000$televisionSessionId\u0000$deviceId"
