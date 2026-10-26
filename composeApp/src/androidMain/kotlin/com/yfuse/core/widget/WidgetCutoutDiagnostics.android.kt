package com.yfuse.core.widget

import android.content.Context
import android.os.SystemClock
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.DiagnosticLogStore
import com.yfuse.core.logging.diagnosticOrigin
import com.yfuse.core.logging.diagnosticRootCause
import com.yfuse.core.logging.diagnosticTypeName
import com.yfuse.core.logging.redactDiagnosticAttributes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import javax.net.ssl.SSLException

internal object WidgetCutoutDiagnosticStore {
    private const val MAX_EVENTS = 128
    private val events = ArrayDeque<JsonObject>()

    fun initialize(context: Context) {
        val application = context.applicationContext
        DiagnosticLogStore.registerExportArtifact("widget-cutout.json") { snapshot(application) }
    }

    fun record(
        event: String,
        attributes: Map<String, String>,
        warning: Boolean,
    ) {
        val safe =
            redactDiagnosticAttributes(attributes).entries.take(32).associate { (key, value) ->
                key.take(64) to value.take(256)
            }
        synchronized(events) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(
                JsonObject(
                    mapOf(
                        "timestamp" to JsonPrimitive(Instant.now().toString()),
                        "event" to JsonPrimitive(event.take(64)),
                        "attributes" to JsonObject(safe.mapValues { JsonPrimitive(it.value) }),
                    ),
                ),
            )
        }
        if (warning) {
            AppLog.warning("widget_cutout", event, attributes = safe)
        } else {
            AppLog.info("widget_cutout", event, attributes = safe)
        }
    }

    private fun snapshot(context: Context): String {
        val preferences = runCatching { widgetCutoutPreferences() }.getOrNull()
        val configuration = preferences?.configuration?.value
        val files = File(context.noBackupFilesDir, POOF_CACHE_DIRECTORY).listFiles().orEmpty().take(4096)
        return JsonObject(
            mapOf(
                "scope" to JsonPrimitive("Current process; earlier events remain in logs/*.jsonl"),
                "enabled" to JsonPrimitive(configuration?.enabled?.toString() ?: "unavailable"),
                "configured" to JsonPrimitive(configuration?.configured?.toString() ?: "unavailable"),
                "status" to JsonPrimitive(preferences?.status?.value.orEmpty()),
                "cacheFiles" to JsonPrimitive(files.count { it.isFile }),
                "cacheBytes" to JsonPrimitive(files.filter { it.isFile }.sumOf(File::length)),
                "events" to JsonArray(synchronized(events) { events.toList() }),
            ),
        ).toString()
    }
}

internal actual fun recordWidgetCutoutDiagnostic(
    event: String,
    attributes: Map<String, String>,
    warning: Boolean,
) = WidgetCutoutDiagnosticStore.record(event, attributes, warning)

internal actual fun widgetCutoutFailureAttributes(failure: Throwable): Map<String, String> {
    val root = failure.diagnosticRootCause()
    return mapOf(
        "failureType" to root.diagnosticTypeName(),
        "failureOrigin" to root.diagnosticOrigin(),
        "failureKind" to
            when (root) {
                is UnknownHostException -> "dns"
                is SocketTimeoutException -> "timeout"
                is SSLException -> "tls"
                is PoofApiException -> "http"
                is PoofResponseException -> root.reason
                else -> "processing"
            },
    )
}

/** One correlation id per queued processing attempt; elapsed time uses a monotonic clock. */
internal class PoofRequestDiagnostics(
    private val source: String,
) {
    private val reference = UUID.randomUUID().toString().take(8)
    private val started = SystemClock.elapsedRealtime()
    var stage: String = "queue"

    fun event(
        event: String,
        attributes: Map<String, String> = emptyMap(),
        warning: Boolean = false,
    ) = logWidgetCutout(
        event,
        source,
        attributes +
            mapOf(
                "requestRef" to reference,
                "stage" to stage,
                "elapsedMs" to (SystemClock.elapsedRealtime() - started).toString(),
            ),
        warning,
    )

    fun failure(failure: Throwable) = event("request_failed", widgetCutoutFailureAttributes(failure), warning = true)
}
