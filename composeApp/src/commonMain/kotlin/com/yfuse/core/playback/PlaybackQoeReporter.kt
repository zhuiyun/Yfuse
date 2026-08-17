package com.yfuse.core.playback

import com.russhwolf.settings.Settings
import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.data.PLAYBACK_QOE_OUTBOX_KEY
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.watch.protocol.AnonymousPlaybackQoeReport
import com.yfuse.watch.protocol.QoeProtocol
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Opt-in, anonymous, bounded and at-least-once delivery of bucketed playback quality reports. */
class PlaybackQoeReporter(
    private val settings: Settings,
    private val preferences: PlaybackPreferences,
    private val client: HttpClient,
    val appVersion: String,
    baseUrl: String = ACCOUNT_BASE_URL,
) {
    private val endpoint =
        baseUrl.trimEnd('/').also {
            require(it.startsWith("https://")) { "QoE aggregation requires HTTPS" }
        } + "/api/v1/qoe"
    private val lock = Mutex()
    private val serializer = ListSerializer(AnonymousPlaybackQoeReport.serializer())
    private val json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }

    suspend fun submit(report: AnonymousPlaybackQoeReport): Boolean {
        if (!preferences.anonymousQoeSharing.value) {
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
            client
                .post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(report)
                }.status in setOf(HttpStatusCode.Accepted, HttpStatusCode.OK, HttpStatusCode.NoContent)
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
