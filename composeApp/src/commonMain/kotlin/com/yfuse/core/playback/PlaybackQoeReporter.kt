package com.yfuse.core.playback

import com.russhwolf.settings.Settings
import com.yfuse.backend.BackendEndpoints
import com.yfuse.backend.HttpQoeBackendApi
import com.yfuse.backend.QoeBackendApi
import com.yfuse.core.data.PLAYBACK_QOE_OUTBOX_KEY
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.watch.protocol.AnonymousPlaybackQoeReport
import com.yfuse.watch.protocol.QoeProtocol
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Opt-in, anonymous, bounded and at-least-once delivery of bucketed playback quality reports. */
class PlaybackQoeReporter(
    private val settings: Settings,
    private val preferences: PlaybackPreferences,
    private val api: QoeBackendApi,
    val appVersion: String,
) {
    constructor(
        settings: Settings,
        preferences: PlaybackPreferences,
        client: HttpClient,
        appVersion: String,
        baseUrl: String = BackendEndpoints.ORIGIN,
    ) : this(settings, preferences, HttpQoeBackendApi(client, baseUrl), appVersion)

    private val lock = Mutex()
    private val serializer = ListSerializer(AnonymousPlaybackQoeReport.serializer())
    private val json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }

    suspend fun submit(report: AnonymousPlaybackQoeReport): Boolean {
        if (!api.enabled || !preferences.anonymousQoeSharing.value) {
            settings.remove(PLAYBACK_QOE_OUTBOX_KEY)
            return false
        }
        if (!QoeProtocol.isValid(report)) return false
        return lock.withLock {
            if (!preferences.anonymousQoeSharing.value) {
                writeOutbox(emptyList())
                return@withLock false
            }
            var pending = (readOutbox() + report).takeLast(MAX_QOE_OUTBOX_REPORTS)
            writeOutbox(pending)
            while (pending.isNotEmpty()) {
                if (!preferences.anonymousQoeSharing.value) {
                    writeOutbox(emptyList())
                    return@withLock false
                }
                val sent = send(pending.first())
                if (!sent) {
                    if (!preferences.anonymousQoeSharing.value) writeOutbox(emptyList())
                    return@withLock false
                }
                pending = pending.drop(1)
                writeOutbox(pending)
            }
            pending.isEmpty()
        }
    }

    internal fun pendingReports(): Int = readOutbox().size

    private suspend fun send(report: AnonymousPlaybackQoeReport): Boolean =
        try {
            api.send(report)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            false
        }

    private fun readOutbox(): List<AnonymousPlaybackQoeReport> =
        settings
            .getStringOrNull(PLAYBACK_QOE_OUTBOX_KEY)
            ?.let { raw -> runCatching { json.decodeFromString(serializer, raw) }.getOrNull() }
            .orEmpty()
            .filter(QoeProtocol::isValid)
            .takeLast(MAX_QOE_OUTBOX_REPORTS)

    private fun writeOutbox(reports: List<AnonymousPlaybackQoeReport>) {
        if (reports.isEmpty()) {
            settings.remove(PLAYBACK_QOE_OUTBOX_KEY)
        } else {
            settings.putString(PLAYBACK_QOE_OUTBOX_KEY, json.encodeToString(serializer, reports))
        }
    }
}

private const val MAX_QOE_OUTBOX_REPORTS = 20
