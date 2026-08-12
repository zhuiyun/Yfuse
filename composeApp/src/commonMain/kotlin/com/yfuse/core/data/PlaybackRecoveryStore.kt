package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlaybackRecoverySnapshot(
    val itemId: String,
    val title: String,
    val serverId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val engine: String,
    val updatedAtEpochMs: Long,
)

enum class PlaybackRecoveryEligibility {
    Eligible,
    AuthenticationRequired,
    InsufficientProgress,
    NearEnd,
    TooOld,
    ServerMissing,
    Invalid,
}

data class PlaybackRecoveryEvaluation(
    val snapshot: PlaybackRecoverySnapshot,
    val eligibility: PlaybackRecoveryEligibility,
    val server: SavedServer? = null,
) {
    val shouldPrompt: Boolean
        get() = eligibility == PlaybackRecoveryEligibility.Eligible ||
            eligibility == PlaybackRecoveryEligibility.AuthenticationRequired
}

private const val MIN_RECOVERY_POSITION_MS = 10_000L
private const val FINISHED_RECOVERY_WINDOW_MS = 30_000L
private const val MAX_RECOVERY_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
private const val MAX_RECOVERY_FUTURE_SKEW_MS = 5L * 60L * 1_000L

/** Pure cold-start policy, kept separate from Compose so process-death cases are testable. */
fun evaluatePlaybackRecovery(
    snapshot: PlaybackRecoverySnapshot,
    servers: List<SavedServer>,
    nowEpochMs: Long,
): PlaybackRecoveryEvaluation {
    if (
        snapshot.itemId.isBlank() || snapshot.serverId.isNullOrBlank() ||
        snapshot.positionMs < 0L || snapshot.durationMs < 0L ||
        snapshot.updatedAtEpochMs <= 0L
    ) {
        return PlaybackRecoveryEvaluation(snapshot, PlaybackRecoveryEligibility.Invalid)
    }
    if (snapshot.positionMs < MIN_RECOVERY_POSITION_MS) {
        return PlaybackRecoveryEvaluation(snapshot, PlaybackRecoveryEligibility.InsufficientProgress)
    }
    if (
        snapshot.updatedAtEpochMs > nowEpochMs &&
        snapshot.updatedAtEpochMs - nowEpochMs > MAX_RECOVERY_FUTURE_SKEW_MS
    ) {
        return PlaybackRecoveryEvaluation(snapshot, PlaybackRecoveryEligibility.Invalid)
    }
    val ageMs = (nowEpochMs - snapshot.updatedAtEpochMs).coerceAtLeast(0L)
    if (ageMs > MAX_RECOVERY_AGE_MS) {
        return PlaybackRecoveryEvaluation(snapshot, PlaybackRecoveryEligibility.TooOld)
    }
    if (
        snapshot.durationMs > 0L &&
        snapshot.durationMs - snapshot.positionMs <= FINISHED_RECOVERY_WINDOW_MS
    ) {
        return PlaybackRecoveryEvaluation(snapshot, PlaybackRecoveryEligibility.NearEnd)
    }
    val serverId = snapshot.serverId
        ?: return PlaybackRecoveryEvaluation(snapshot, PlaybackRecoveryEligibility.Invalid)
    val server = servers.firstOrNull { it.id == serverId || serverId in it.previousIds }
        ?: return PlaybackRecoveryEvaluation(snapshot, PlaybackRecoveryEligibility.ServerMissing)
    return PlaybackRecoveryEvaluation(
        snapshot = snapshot,
        eligibility = if (server.accessToken.isBlank()) {
            PlaybackRecoveryEligibility.AuthenticationRequired
        } else {
            PlaybackRecoveryEligibility.Eligible
        },
        server = server,
    )
}

/**
 * Process-death-safe playback checkpoint. Authenticated media URLs are
 * deliberately excluded; they are rebuilt from the saved server on resume.
 */
class PlaybackRecoveryStore(private val settings: Settings) {
    private companion object {
        const val KEY = "playback.recovery.v1"
        const val WRITE_INTERVAL_MS = 5_000L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _snapshot = MutableStateFlow(load())
    val snapshot: StateFlow<PlaybackRecoverySnapshot?> = _snapshot.asStateFlow()
    private var lastWriteEpochMs = 0L
    private var lastItemId: String? = _snapshot.value?.itemId
    private var persistenceFailureReported = false
    /** A singleton store is process-scoped: configuration changes must not offer it twice. */
    private var startupSnapshotChecked = false

    fun takeStartupEvaluation(
        servers: List<SavedServer>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PlaybackRecoveryEvaluation? {
        if (startupSnapshotChecked) return null
        startupSnapshotChecked = true
        return _snapshot.value?.let { evaluatePlaybackRecovery(it, servers, nowEpochMs) }
    }

    fun record(
        itemId: String,
        title: String,
        serverId: String?,
        positionMs: Long,
        durationMs: Long,
        engine: String,
        force: Boolean = false,
    ) {
        if (itemId.isBlank() || positionMs < 0L) return
        val now = System.currentTimeMillis()
        val changedItem = itemId != lastItemId
        val value = PlaybackRecoverySnapshot(
            itemId = itemId,
            title = title,
            serverId = serverId,
            positionMs = positionMs,
            durationMs = durationMs,
            engine = engine,
            updatedAtEpochMs = now,
        )
        _snapshot.value = value
        if (!force && !changedItem && now - lastWriteEpochMs < WRITE_INTERVAL_MS) return
        runCatching {
            settings.putString(KEY, json.encodeToString(PlaybackRecoverySnapshot.serializer(), value))
        }.onSuccess {
            if (persistenceFailureReported) {
                AppLog.info(
                    category = "playback.recovery",
                    event = "persistence_recovered",
                    message = "Playback recovery persistence recovered",
                )
            }
            persistenceFailureReported = false
            lastWriteEpochMs = now
            lastItemId = itemId
        }.onFailure {
            if (!persistenceFailureReported) {
                AppLog.error(
                    category = "playback.recovery",
                    event = "persist_failed",
                    message = "Failed to persist playback recovery checkpoint",
                    throwable = it,
                    attributes = mapOf("engine" to engine),
                )
            }
            persistenceFailureReported = true
        }
    }

    fun clear() {
        _snapshot.value = null
        runCatching { settings.remove(KEY) }
            .onFailure {
                AppLog.warning(
                    category = "playback.recovery",
                    event = "clear_failed",
                    message = "Failed to clear playback recovery checkpoint",
                    throwable = it,
                )
            }
        lastItemId = null
        lastWriteEpochMs = 0L
    }

    private fun load(): PlaybackRecoverySnapshot? {
        val raw = settings.getStringOrNull(KEY) ?: return null
        return runCatching {
            json.decodeFromString(PlaybackRecoverySnapshot.serializer(), raw)
        }.onFailure {
            AppLog.warning(
                category = "playback.recovery",
                event = "stored_checkpoint_invalid",
                message = "Stored playback recovery checkpoint could not be decoded",
                throwable = it,
            )
        }.getOrNull()
    }
}
