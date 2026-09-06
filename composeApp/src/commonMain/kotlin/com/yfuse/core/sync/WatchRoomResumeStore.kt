package com.yfuse.core.sync

import com.yfuse.core.logging.AppLog
import com.yfuse.core.security.SecureStore
import com.yfuse.watch.protocol.WatchProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The room a session was last welcomed into, kept so a process restart can rejoin it.
 *
 * [resumeCapability] and [hostCapability] are private, room-scoped bearers: they belong in the
 * platform-encrypted [SecureStore], never in plain settings, and are dropped the moment the
 * member leaves, is kicked, or the room ends.
 */
@Serializable
data class PersistedRoomResume(
    val roomCode: String,
    /** Empty when the room was entered by code alone and the server has not named a title yet. */
    val mediaKey: String,
    val resumeCapability: String? = null,
    val hostCapability: String? = null,
) {
    internal val isWellFormed: Boolean
        get() =
            WatchProtocol.isValidRoomCode(roomCode) &&
                (mediaKey.isEmpty() || WatchProtocol.isValidMediaKey(mediaKey)) &&
                (resumeCapability == null || WatchProtocol.isValidCapability(resumeCapability)) &&
                (hostCapability == null || WatchProtocol.isValidCapability(hostCapability))
}

/** Reads and writes the single [PersistedRoomResume] slot; every failure degrades to "nothing saved". */
internal class WatchRoomResumeStore(
    private val secureStore: SecureStore?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): PersistedRoomResume? {
        val store = secureStore ?: return null
        val raw =
            runCatching { store.get(KEY) }
                .onFailure { logFailure("resume_read_failed", it) }
                .getOrNull() ?: return null
        val parsed =
            runCatching { json.decodeFromString(PersistedRoomResume.serializer(), raw.decodeToString()) }
                .getOrNull()
        if (parsed == null || !parsed.isWellFormed) {
            clear()
            return null
        }
        return parsed
    }

    fun save(resume: PersistedRoomResume) {
        val store = secureStore ?: return
        if (!resume.isWellFormed) return
        runCatching {
            store.put(KEY, json.encodeToString(PersistedRoomResume.serializer(), resume).encodeToByteArray())
        }.onFailure { logFailure("resume_write_failed", it) }
    }

    fun clear() {
        val store = secureStore ?: return
        runCatching { store.remove(KEY) }.onFailure { logFailure("resume_clear_failed", it) }
    }

    private fun logFailure(
        event: String,
        error: Throwable,
    ) {
        AppLog.warning(
            category = "watch_together",
            event = event,
            message = "Watch-together room resume could not be persisted",
            throwable = error,
        )
    }

    private companion object {
        const val KEY = "watch_together.room_resume.v1"
    }
}
