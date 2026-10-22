package com.yfuse.core.remote

import com.yfuse.core.logging.AppLog
import com.yfuse.core.security.SecureStore
import com.yfuse.watch.protocol.WatchProtocol
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The pairing tokens televisions' admissions gave this phone, by television session, under
 * [WatchProtocol.CAPABILITY_REMOTE_PAIRING_TOKEN]. A token proves to the relay that this phone is
 * the one a television let in, so another device of the account cannot claim its id. Tokens are
 * bearer secrets: they live in the platform-encrypted [SecureStore], never in plain settings.
 */
interface RemotePairingTokenStore {
    fun load(televisionSessionId: String): String?

    fun save(
        televisionSessionId: String,
        token: String,
    )

    companion object {
        /** Keeps nothing: every join is a first join, and the television asks as it always did. */
        val None: RemotePairingTokenStore =
            object : RemotePairingTokenStore {
                override fun load(televisionSessionId: String): String? = null

                override fun save(
                    televisionSessionId: String,
                    token: String,
                ) = Unit
            }

        fun secure(store: SecureStore): RemotePairingTokenStore = SecureRemotePairingTokenStore(store)
    }
}

private class SecureRemotePairingTokenStore(
    private val store: SecureStore,
) : RemotePairingTokenStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val lock = Any()

    override fun load(televisionSessionId: String): String? =
        synchronized(lock) { read()[televisionSessionId]?.takeIf(WatchProtocol::isValidPairingToken) }

    override fun save(
        televisionSessionId: String,
        token: String,
    ) {
        if (!WatchProtocol.isValidPairingToken(token)) return
        synchronized(lock) {
            // The newest pairings, a household's worth of televisions.
            val tokens = (read() - televisionSessionId) + (televisionSessionId to token)
            val kept =
                tokens.entries
                    .toList()
                    .takeLast(MAX_TELEVISIONS)
                    .associate { it.key to it.value }
            runCatching { store.put(KEY, json.encodeToString(serializer, kept).encodeToByteArray()) }
                .onFailure { failure ->
                    AppLog.warning(
                        category = "remote_control",
                        event = "pairing_token_write_failed",
                        message = "Remote-control pairing token could not be saved",
                        throwable = failure,
                    )
                }
        }
    }

    private fun read(): Map<String, String> =
        runCatching { store.get(KEY)?.decodeToString()?.let { json.decodeFromString(serializer, it) } }
            .getOrNull()
            .orEmpty()

    private companion object {
        const val KEY = "remote_control.pairing_tokens.v1"
        const val MAX_TELEVISIONS = 16
    }
}
