package com.yfuse.feature.player

import android.content.Context
import com.yfuse.backend.BackendAccess
import com.yfuse.backend.BackendBinaryTransport
import com.yfuse.backend.BackendEndpoints
import com.yfuse.backend.BackendFeature
import com.yfuse.core.logging.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference

/**
 * Restrict-only playback policy used to stop a known-bad native path without shipping a new APK.
 *
 * The server cannot enable a build-time-disabled capability, select an arbitrary library, or
 * change a media URL. Revisions are monotonic and retained after expiry so an older policy cannot
 * be replayed. An unavailable or malformed endpoint leaves the locally packaged behavior intact.
 */
internal object PlaybackRemotePolicyRegistry {
    private const val PREFS = "yfuse_playback_remote_policy_v1"
    private const val MAX_POLICY_BYTES = 32 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val transport = BackendBinaryTransport()
    private val active = AtomicReference(PlaybackRemotePolicyState())
    private lateinit var appContext: Context

    @Synchronized
    fun initialize(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        appContext = context.applicationContext
        if (!BackendAccess.Default.enabled) {
            active.set(PlaybackRemotePolicyState())
            return
        }
        val preferences = prefs()
        val revision = preferences.getLong("revision", 0L)
        val expiresAt = preferences.getLong("expiresAtEpochMs", 0L)
        val disabled =
            preferences
                .getStringSet("disabledPaths", emptySet())
                .orEmpty()
                .mapNotNull(PlaybackRemotePath::fromWireValue)
                .toSet()
        active.set(
            PlaybackRemotePolicyState(
                revision = revision,
                expiresAtEpochMs = expiresAt,
                disabledPaths = disabled.takeIf { expiresAt > nowEpochMs }.orEmpty(),
            ),
        )
    }

    fun isDisabled(
        path: PlaybackRemotePath,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!BackendAccess.Default.enabled) return false
        val state = active.get()
        return state.expiresAtEpochMs > nowEpochMs && path in state.disabledPaths
    }

    /** Called from the update manager's IO dispatcher; update checking still succeeds on failure. */
    fun refreshFromNetwork(nowEpochMs: Long = System.currentTimeMillis()) {
        if (!BackendAccess.Default.enabled) return
        // No policy has ever been published for most builds. Re-asking on every process start
        // costs a request and a log line per launch while the answer cannot change that fast.
        if (nowEpochMs < prefs().getLong(KEY_UNPUBLISHED_UNTIL, 0L)) return
        val document =
            transport.fetchDocument(
                url = BackendEndpoints.PLAYBACK_POLICY,
                feature = BackendFeature.PlaybackPolicy,
                maxBytes = MAX_POLICY_BYTES,
                connectTimeoutMillis = 5_000,
                readTimeoutMillis = 5_000,
                tooLargeMessage = "Playback policy is too large",
            )
        val status = document.statusCode
        if (status == HttpURLConnection.HTTP_NOT_FOUND || status == HttpURLConnection.HTTP_GONE) {
            prefs()
                .edit()
                .putLong(KEY_UNPUBLISHED_UNTIL, nowEpochMs + UNPUBLISHED_RECHECK_INTERVAL_MS)
                .apply()
            throw PlaybackRemotePolicyUnpublishedException(status)
        }
        check(status == HttpURLConnection.HTTP_OK) { "Playback policy HTTP $status" }
        prefs().edit().remove(KEY_UNPUBLISHED_UNTIL).apply()
        apply(json.decodeFromString<PlaybackRemotePolicyDocument>(document.bytes.decodeToString()), nowEpochMs)
    }

    @Synchronized
    internal fun apply(
        document: PlaybackRemotePolicyDocument,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!BackendAccess.Default.enabled) return false
        val currentRevision = prefs().getLong("revision", 0L)
        val sanitized = sanitizePlaybackRemotePolicy(document, currentRevision, nowEpochMs) ?: return false
        prefs()
            .edit()
            .putLong("revision", sanitized.revision)
            .putLong("expiresAtEpochMs", sanitized.expiresAtEpochMs)
            .putStringSet("disabledPaths", sanitized.disabledPaths.mapTo(mutableSetOf()) { it.wireValue })
            .apply()
        active.set(sanitized)
        AppLog.warning(
            category = "player.remote_policy",
            event = "policy_applied",
            message = "A newer restrict-only playback policy was applied",
            attributes =
                mapOf(
                    "revision" to sanitized.revision.toString(),
                    "disabledPaths" to sanitized.disabledPaths.joinToString(",") { it.wireValue },
                ),
        )
        return true
    }

    fun diagnosticSummary(nowEpochMs: Long = System.currentTimeMillis()): String {
        val state = active.get()
        val effective = state.disabledPaths.takeIf { state.expiresAtEpochMs > nowEpochMs }.orEmpty()
        return buildString {
            appendLine("remotePolicy.revision=${state.revision}")
            appendLine("remotePolicy.expiresAtEpochMs=${state.expiresAtEpochMs}")
            appendLine("remotePolicy.disabled=${effective.joinToString(",") { it.wireValue }}")
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The endpoint answered that no policy is published.
 *
 * This is the normal state for a build that has never needed a remote restriction, so it must be
 * distinguishable from a refresh that genuinely failed.
 */
internal class PlaybackRemotePolicyUnpublishedException(
    val statusCode: Int,
) : Exception("Playback policy is not published (HTTP $statusCode)")

private const val KEY_UNPUBLISHED_UNTIL = "unpublishedUntilEpochMs"
private const val UNPUBLISHED_RECHECK_INTERVAL_MS = 6L * 60L * 60L * 1_000L

internal enum class PlaybackRemotePath(
    val wireValue: String,
) {
    YCoreAll("ycore.all"),
    YCoreDemux("ycore.demux"),
    YCoreGpu("ycore.gpu"),
    Mpv("mpv"),
    Mdk("mdk"),
    ;

    companion object {
        fun fromWireValue(value: String): PlaybackRemotePath? = entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
internal data class PlaybackRemotePolicyDocument(
    val revision: Long,
    val expiresAtEpochMs: Long,
    val disabledPaths: Set<String> = emptySet(),
)

internal data class PlaybackRemotePolicyState(
    val revision: Long = 0L,
    val expiresAtEpochMs: Long = 0L,
    val disabledPaths: Set<PlaybackRemotePath> = emptySet(),
)

internal fun sanitizePlaybackRemotePolicy(
    document: PlaybackRemotePolicyDocument,
    currentRevision: Long,
    nowEpochMs: Long,
): PlaybackRemotePolicyState? {
    if (document.revision <= currentRevision || document.revision <= 0L) return null
    if (document.expiresAtEpochMs <= nowEpochMs) return null
    if (document.expiresAtEpochMs - nowEpochMs > MAX_PLAYBACK_POLICY_LIFETIME_MS) return null
    return PlaybackRemotePolicyState(
        revision = document.revision,
        expiresAtEpochMs = document.expiresAtEpochMs,
        disabledPaths = document.disabledPaths.mapNotNull(PlaybackRemotePath::fromWireValue).toSet(),
    )
}

private const val MAX_PLAYBACK_POLICY_LIFETIME_MS = 31L * 24L * 60L * 60L * 1_000L
